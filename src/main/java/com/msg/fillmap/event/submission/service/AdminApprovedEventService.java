package com.msg.fillmap.event.submission.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.submission.dto.AdminApprovedEventListResponseDto;
import com.msg.fillmap.event.submission.dto.AdminEventUnpublishRequestDto;
import com.msg.fillmap.event.submission.dto.AdminEventUnpublishResponseDto;
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatus;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.mission.service.MissionRegistrationService;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 승인 행사의 조회와 노출 관리 (MSG-500 §API 5·6). 심사(신청)와 갈라 둔 것은 다루는 대상이 다르기
 * 때문이다 — 여기서 보는 것은 이미 승인돼 지도에 실린 행사이고, 원천 테이블만 신청과 같다(D-11).
 *
 * <p>탭 상태는 저장하지 않는다. 조회 시점 <b>KST 오늘</b>과 행사 기간으로 파생하므로 자정이 지나면 같은
 * 행이 다른 탭으로 옮겨간다 — 저장했다면 매일 도는 배치가 필요했을 상태다.
 *
 * <p>중지 통지는 <b>커밋 뒤</b>다(MSG-499 발급 발송 선례). 안에 두면 SES 왕복이 DB 커넥션을 붙들고,
 * 발송 성공 뒤 커밋이 실패하면 "중지됐다고 통지했는데 아직 노출 중"인 역방향 구멍이 생긴다.
 */
@Slf4j
@Service
public class AdminApprovedEventService {

	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	/** 탭 판정의 "오늘"은 관리자가 보는 날짜라 KST 다 (승인 번호 연도 라벨과 같은 성격). */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final String MAIL_SUBJECT = "[필맵] 등재 행사 노출 중지 안내";
	private static final String MAIL_BODY_FORMAT = """
		승인된 행사의 지도 노출이 중지되었습니다.

		행사명: %s
		승인 번호: %s
		중지 사유: %s

		문의가 있으시면 이 메일에 회신해 주세요.""";

	private final EventSubmissionRepository submissionRepository;
	private final MissionRegistrationService missionRegistrationService;
	private final UserRepository userRepository;
	private final MailSender mailSender;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	/** 프로덕션 생성자 — clock 을 systemUTC 로 고정해 전체 생성자로 위임한다 (심사 서비스와 같은 형태). */
	@Autowired
	public AdminApprovedEventService(EventSubmissionRepository submissionRepository,
		MissionRegistrationService missionRegistrationService, UserRepository userRepository,
		MailSender mailSender, TransactionTemplate transactionTemplate) {
		this(submissionRepository, missionRegistrationService, userRepository, mailSender, transactionTemplate,
			Clock.systemUTC());
	}

	public AdminApprovedEventService(EventSubmissionRepository submissionRepository,
		MissionRegistrationService missionRegistrationService, UserRepository userRepository,
		MailSender mailSender, TransactionTemplate transactionTemplate, Clock clock) {
		this.submissionRepository = submissionRepository;
		this.missionRegistrationService = missionRegistrationService;
		this.userRepository = userRepository;
		this.mailSender = mailSender;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
	}

	/**
	 * 승인 행사 목록 (§API 5, FR-25). 탭 필터와 건수 3종이 <b>같은 파생식</b>을 쓴다 — 식이 갈리면 뱃지
	 * 숫자와 목록 건수가 어긋난다. 거부 판정은 심사 큐와 같은 순서다: 탭 파싱 실패(13455) → 페이지 범위(13456).
	 */
	@Transactional(readOnly = true)
	public AdminApprovedEventListResponseDto getEvents(String status, int page, int size) {
		String tab = parseTab(status);
		if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE
			|| (long) page * size > Integer.MAX_VALUE) {
			throw new ApiException(EventErrorCode.INVALID_PAGE_RANGE);
		}
		LocalDate today = LocalDate.now(clock.withZone(KST));
		return AdminApprovedEventListResponseDto.of(
			submissionRepository.findApprovedPageByTab(tab, today, PageRequest.of(page, size)),
			submissionRepository.countApprovedByTab(Tab.EXPOSED.name(), today),
			submissionRepository.countApprovedByTab(Tab.UPCOMING.name(), today),
			submissionRepository.countApprovedByTab(Tab.ENDED.name(), today));
	}

	/**
	 * 노출 중지 (§API 6, FR-20). 조건부 UPDATE 하나가 "승인됐고 아직 중지되지 않은 행"에만 걸려 동시 중지의
	 * 늦은 쪽이 사유를 덮지 못하게 하고, 같은 트랜잭션에서 승인 산출물을 숨긴다.
	 *
	 * <p>미션 숨김은 소유 도메인 서비스를 거친다 — 스냅숏 무효화가 그쪽 책임이라 여기서 missions 를 직접
	 * 건드리면 지도 목록이 최대 1시간 중지된 미션을 계속 그린다.
	 *
	 * <p>산출물 링크가 없는 승인 행사(참여형)는 지금 만들어질 수 없다 — 승인 분기가 미션 하나뿐이다.
	 * 참여형 위치 숨김과 부모 회차 노출 영역 재계산은 그 분기와 함께 온다(MSG-502 이후).
	 */
	public AdminEventUnpublishResponseDto unpublish(Long submissionId, AdminEventUnpublishRequestDto request) {
		Unpublished unpublished = transactionTemplate.execute(status -> {
			LocalDateTime now = LocalDateTime.now(clock);
			if (submissionRepository.unpublishApproved(submissionId, request.reason(), now) == 0) {
				throw unpublishFailure(submissionId);
			}
			EventSubmission submission = submissionRepository.findById(submissionId)
				.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
			if (submission.getPublishedMissionId() != null) {
				missionRegistrationService.hide(submission.getPublishedMissionId(), now);
			}
			String email = userRepository.findById(submission.getUserId())
				.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND))
				.getEmail();
			return new Unpublished(email, submission.getTitle(), submission.getApprovalNo(), now);
		});
		return new AdminEventUnpublishResponseDto(submissionId, unpublished.at(),
			notify(unpublished, request.reason()));
	}

	/**
	 * 사유 통지 — 실패는 삼키고 false 로 알린다 (MSG-499 발급 발송 선례). 중지는 이미 커밋됐고, 저장된 사유가
	 * 수기 재통지의 재료라 발송 실패가 중지를 되돌릴 이유가 되지 않는다. 재발송 API 는 만들지 않는다.
	 */
	private boolean notify(Unpublished unpublished, String reason) {
		try {
			mailSender.send(unpublished.email(), MAIL_SUBJECT,
				MAIL_BODY_FORMAT.formatted(unpublished.title(), unpublished.approvalNo(), reason));
			return true;
		} catch (RuntimeException e) {
			log.error("행사 노출 중지 통지 발송 실패 — 중지는 유지된다: to={}", unpublished.email(), e);
			return false;
		}
	}

	/** 조건부 UPDATE 가 0행일 때의 이유 — 없거나 미승인이면 13430, 이미 중지면 13453 이다. */
	private ApiException unpublishFailure(Long submissionId) {
		EventSubmission submission = submissionRepository.findById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		if (submission.getStatus() != EventSubmissionStatus.APPROVED) {
			// 승인되지 않은 신청은 노출 행사가 아니다 — 관리자에게도 "그런 행사는 없다"가 맞는 답이다.
			return new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND);
		}
		return new ApiException(EventErrorCode.EVENT_ALREADY_UNPUBLISHED);
	}

	/** 클라이언트 문자열 → 탭 (심사 큐 parseStatus 와 같은 규칙). 조용한 기본값 폴백 없이 13455 로 거른다. */
	private String parseTab(String status) {
		try {
			return Tab.valueOf(status.toUpperCase(Locale.ROOT)).name();
		} catch (IllegalArgumentException e) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_STATUS_FILTER);
		}
	}

	/** 파생 탭 3종 — 저장 컬럼이 아니라 조회 시점 계산값이라 enum 이 이 서비스 안에 산다. */
	private enum Tab {
		EXPOSED,
		UPCOMING,
		ENDED
	}

	/** 커밋 뒤 발송에 필요한 값만 트랜잭션 밖으로 들고 나온다 — 엔티티를 밖으로 흘리지 않는다. */
	private record Unpublished(String email, String title, String approvalNo, LocalDateTime at) {
	}
}
