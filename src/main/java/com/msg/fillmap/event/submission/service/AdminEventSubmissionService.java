package com.msg.fillmap.event.submission.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.submission.dto.AdminEventSubmissionDetailResponseDto;
import com.msg.fillmap.event.submission.dto.AdminEventSubmissionListResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionApproveResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionAreaRectDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionHistoryResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionRejectRequestDto;
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionAreaRect;
import com.msg.fillmap.event.submission.entity.EventSubmissionLocation;
import com.msg.fillmap.event.submission.entity.EventSubmissionReasonCode;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatus;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatusHistory;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.event.submission.repository.EventSubmissionStatusHistoryRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.AreaCell;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.service.MissionRegistrationService;
import com.msg.fillmap.mission.service.MissionRegistrationService.MissionRegistration;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 관리자 심사 (MSG-500 §API 1~4). 행사 운영자 조회(MSG-498)와 <b>존재 은닉이 반대다</b> — 소유 술어가
 * 없고 없는 id 는 그대로 404(13430)다. 관리자는 전체를 보는 주체라 숨길 대상이 없다.
 *
 * <p>승인·반려는 조건부 UPDATE 하나로 상태를 전이한다 — 읽고 확인한 뒤 쓰면 동시 심사 두 건이 같은
 * IN_REVIEW 를 보고 둘 다 성공한다. 전이·이력·산출물 생성·이미지 복사 보상이 전부 한 트랜잭션이라
 * "미션은 생겼는데 신청은 심사 중" 같은 어긋난 절반이 남지 않는다.
 *
 * <p>역할 검사는 여기에 없다. SecurityConfig 의 {@code /api/admin/**} matcher 가 필터 단계에서 거른다
 * (AdminOrgAccountController 선례).
 */
@Service
public class AdminEventSubmissionService {

	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	/** 승인 번호의 연도 라벨과 기간 판정의 "오늘"은 사용자·관리자가 읽는 값이라 KST 다 (D-4, 접수 선례). */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final EventSubmissionRepository submissionRepository;
	private final EventSubmissionStatusHistoryRepository historyRepository;
	private final EventSubmissionImageStore imageStore;
	private final EventSubmissionLocationView locationView;
	private final UserRepository userRepository;
	private final MissionRegistrationService missionRegistrationService;
	private final Clock clock;

	/** 프로덕션 생성자 — clock 을 systemUTC 로 고정해 전체 생성자로 위임한다 (EventSubmissionServiceImpl 선례). */
	@Autowired
	public AdminEventSubmissionService(EventSubmissionRepository submissionRepository,
		EventSubmissionStatusHistoryRepository historyRepository, EventSubmissionImageStore imageStore,
		EventSubmissionLocationView locationView, UserRepository userRepository,
		MissionRegistrationService missionRegistrationService) {
		this(submissionRepository, historyRepository, imageStore, locationView, userRepository,
			missionRegistrationService, Clock.systemUTC());
	}

	public AdminEventSubmissionService(EventSubmissionRepository submissionRepository,
		EventSubmissionStatusHistoryRepository historyRepository, EventSubmissionImageStore imageStore,
		EventSubmissionLocationView locationView, UserRepository userRepository,
		MissionRegistrationService missionRegistrationService, Clock clock) {
		this.submissionRepository = submissionRepository;
		this.historyRepository = historyRepository;
		this.imageStore = imageStore;
		this.locationView = locationView;
		this.userRepository = userRepository;
		this.missionRegistrationService = missionRegistrationService;
		this.clock = clock;
	}

	/**
	 * 심사 큐 (§API 1). 상태 필터 기준 접수 최신순 오프셋 페이징이고, 탭 뱃지용 건수 3종은 필터와 무관한
	 * 전체 집계다. 거부 판정은 처음 걸린 하나를 돌려준다: 상태 파싱 실패(13455) → 페이지 범위 밖(13456).
	 */
	@Transactional(readOnly = true)
	public AdminEventSubmissionListResponseDto getSubmissions(String status, int page, int size) {
		EventSubmissionStatus filter = parseStatus(status);
		// PageRequest.of 에 그냥 넘기면 IllegalArgumentException 이 catch-all 핸들러에서 500 이 된다.
		// 오프셋(page*size)이 int 를 넘는 극단 양수도 같다 (MSG-499 관리자 큐 선례, 대역만 event 다).
		if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE
			|| (long) page * size > Integer.MAX_VALUE) {
			throw new ApiException(EventErrorCode.INVALID_PAGE_RANGE);
		}
		return AdminEventSubmissionListResponseDto.of(
			submissionRepository.findAdminPageByStatus(filter, PageRequest.of(page, size)),
			submissionRepository.countByStatus(EventSubmissionStatus.IN_REVIEW),
			submissionRepository.countByStatus(EventSubmissionStatus.APPROVED),
			submissionRepository.countByStatus(EventSubmissionStatus.REJECTED));
	}

	/**
	 * 심사 상세 (§API 2). 폼 필드 전체에 심사 재료 셋을 더한다 — 신청 계정 정보, 노출 영역 사각형, 이력이다.
	 * 위치 표현은 행사 운영자 상세와 같은 조립기를 쓴다({@link EventSubmissionLocationView}) — 심사자와
	 * 신청자가 서로 다른 그림을 보면 반려 사유가 가리키는 대상이 어긋난다.
	 */
	@Transactional(readOnly = true)
	public AdminEventSubmissionDetailResponseDto getSubmission(Long submissionId) {
		EventSubmission submission = submissionRepository.findWithLocationsById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		// 신청 계정은 FK 가 보장하는 존재라 빈 결과가 정상 흐름에 없다 — 그래도 삼키지 않고 1404 로 드러낸다.
		User applicant = userRepository.findById(submission.getUserId())
			.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));

		return new AdminEventSubmissionDetailResponseDto(
			submission.getId(),
			submission.getSubmissionNo(),
			submission.getType().name(),
			submission.getStatus().name(),
			submission.getTitle(),
			submission.getOrganizerName(),
			submission.getStartsOn(),
			submission.getEndsOn(),
			submission.getOperatingHours(),
			submission.getProgramDescription(),
			submission.getDescription(),
			imageStore.presignGet(submission.getImageKey()),
			applicant.getOrgName(),
			applicant.getNickname(),
			applicant.getEmail(),
			locationView.describe(submission.getLocations()),
			exposureRect(submission.getLocations()),
			historyRepository.findByEventSubmissionIdOrderByIdAsc(submissionId).stream()
				.map(EventSubmissionHistoryResponseDto::from)
				.toList(),
			submission.getCreatedAt(),
			submission.getUpdatedAt());
	}

	/**
	 * 승인 (§API 3, D-1). 실행 순서가 계약이다.
	 * <p>
	 * ① 신청을 읽어 상태와 기간을 먼저 본다 — 여기서 걸러야 승인 번호 시퀀스를 헛되이 소비하지 않는다
	 * (시퀀스는 롤백해도 되돌아오지 않는다). ② 조건부 UPDATE 로 IN_REVIEW → APPROVED 전이와 승인 번호
	 * 부여를 한 문장에 담는다 — 이것이 동시 승인의 진짜 판정이고 ①은 빠른 거절일 뿐이다. ③ 영향 행이
	 * 0이면 재조회로 가른다: 없으면 13430, 있으면 13450(동시 승인의 패자 포함). ④ 이겼으면 이력을 쌓고
	 * 애그리거트를 <b>재로드</b>해 산출물을 만든다 — 벌크 UPDATE 가 영속성 컨텍스트를 우회했으므로 ① 에서
	 * 읽은 엔티티는 스테일한 IN_REVIEW 다.
	 * <p>
	 * 전이·이력·미션 등재·이미지 복사 보상이 전부 이 트랜잭션 하나다. 뒤에서 실패하면 미션도 승인 상태도
	 * 함께 사라지고 공개 이미지 사본은 롤백 보상이 지운다.
	 */
	@Transactional
	public EventSubmissionApproveResponseDto approve(Long submissionId) {
		EventSubmission submission = findForReview(submissionId);
		requirePeriodNotPassed(submission);

		LocalDateTime now = LocalDateTime.now(clock);
		String approvalNo = nextApprovalNo();
		if (submissionRepository.approveInReview(submissionId, approvalNo, now) == 0) {
			throw transitionFailure(submissionId);
		}
		historyRepository.save(EventSubmissionStatusHistory.approved(submissionId, now));

		EventSubmission approved = submissionRepository.findWithLocationsById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		approved.linkPublishedMission(publish(approved));
		return new EventSubmissionApproveResponseDto(submissionId, approvalNo,
			EventSubmissionStatus.APPROVED.name());
	}

	/**
	 * 반려 (§API 4, FR-19). 승인과 같은 원자 전이이고, 사유는 신청 행이 아니라 이력의 반려 행에 쌓인다
	 * (D-3 저장 원천 단일). 항목 코드 검증을 전이보다 먼저 하는 것은 잘못된 요청이 상태를 바꾸지 못하게
	 * 하기 위해서다. <b>메일은 보내지 않는다</b> — 행사 운영자는 콘솔 상세에서 항목과 사유를 본다(D-5).
	 */
	@Transactional
	public void reject(Long submissionId, EventSubmissionRejectRequestDto request) {
		List<EventSubmissionReasonCode> reasonCodes = parseReasonCodes(request.reasonCodes());

		LocalDateTime now = LocalDateTime.now(clock);
		if (submissionRepository.rejectInReview(submissionId, now) == 0) {
			throw transitionFailure(submissionId);
		}
		historyRepository.save(
			EventSubmissionStatusHistory.rejected(submissionId, reasonCodes, request.reasonText(), now));
	}

	/**
	 * 승인 산출물 (D-2) — 지역축제·팝업스토어는 미션 1건이 된다. 위치가 여럿이어도 칩 카드는 하나가
	 * 자연스럽다.
	 * <p>
	 * switch 에 default 를 두지 않는 것은 <b>의도된 미구현 가드</b>다: MSG-502 가 참여형 유형 값을 추가하는
	 * 순간 이 분기가 컴파일되지 않아, 참여형 승인이 채워지지 않은 채 미션으로 잘못 흘러가는 경로가 만들어질
	 * 수 없다. 지금은 V49 CHECK 가 두 값만 허용해 도달 자체가 불가능하다.
	 */
	private long publish(EventSubmission submission) {
		MissionType missionType = switch (submission.getType()) {
			case FESTIVAL -> MissionType.EVENT;   // 지도 홈 "축제" 칩 — 행사 카테고리 "이벤트"와 무관하다
			case POPUP -> MissionType.POPUP;
		};
		// 운영 시간은 팝업만 값을 갖는다 — 접수 검증이 축제의 운영 시간을 애초에 거부하므로 분기가 필요 없다.
		return missionRegistrationService.register(new MissionRegistration(
			missionType,
			submission.getTitle(),
			submission.getStartsOn(),
			submission.getEndsOn(),
			submission.getSubmissionNo(),
			submission.getDescription(),
			submission.getOperatingHours(),
			imageStore.copyToMissionImage(submission.getImageKey()),
			EventSubmissionCells.union(submission.getLocations()).stream().map(AreaCell::gridId).toList()));
	}

	/** 심사 대상 조회 — 없으면 13430, 심사 중이 아니면 13450 (전이 전 빠른 거절). */
	private EventSubmission findForReview(Long submissionId) {
		EventSubmission submission = submissionRepository.findWithLocationsById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		if (submission.getStatus() != EventSubmissionStatus.IN_REVIEW) {
			throw new ApiException(EventErrorCode.SUBMISSION_STATUS_NOT_REVIEWABLE);
		}
		return submission;
	}

	/**
	 * 기간 가드 (D-1) — 접수 검증(MSG-498 D-6)과 같은 규칙을 심사 지연 구간에 다시 건다. 끝난 행사를
	 * 승인하면 지도에 실릴 것이 없는 미션이 생긴다. "오늘"은 관리자가 생각하는 날짜라 KST 다.
	 */
	private void requirePeriodNotPassed(EventSubmission submission) {
		if (submission.getEndsOn().isBefore(LocalDate.now(clock.withZone(KST)))) {
			throw new ApiException(EventErrorCode.SUBMISSION_PERIOD_PASSED);
		}
	}

	/** 조건부 UPDATE 가 0행일 때의 이유 — 그 사이 사라졌으면 13430, 남아 있으면 상태 위반이다. */
	private ApiException transitionFailure(Long submissionId) {
		submissionRepository.findById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		return new ApiException(EventErrorCode.SUBMISSION_STATUS_NOT_REVIEWABLE);
	}

	/**
	 * 반려 항목 코드 파싱 (§API 4) — 빈 목록·허용 밖 값·중복을 전부 13454 로 낸다. Jackson enum 역직렬화에
	 * 맡기지 않는 이유는 그 실패가 공통 400 으로 뭉개져 "무엇이 잘못됐는지"를 화면이 구분하지 못해서다.
	 */
	private List<EventSubmissionReasonCode> parseReasonCodes(List<String> rawCodes) {
		if (rawCodes == null || rawCodes.isEmpty()) {
			throw new ApiException(EventErrorCode.INVALID_REJECT_REASON);
		}
		List<EventSubmissionReasonCode> codes = new ArrayList<>();
		for (String rawCode : rawCodes) {
			if (rawCode == null) {
				throw new ApiException(EventErrorCode.INVALID_REJECT_REASON);
			}
			try {
				codes.add(EventSubmissionReasonCode.valueOf(rawCode.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				throw new ApiException(EventErrorCode.INVALID_REJECT_REASON, e);
			}
		}
		if (codes.size() != Set.copyOf(codes).size()) {
			throw new ApiException(EventErrorCode.INVALID_REJECT_REASON);
		}
		return codes;
	}

	/** {@code APR-{KST 연도}-{4자리 0패딩 순번}} (D-4) — 신청 번호와 같은 구조의 전역 시퀀스다. */
	private String nextApprovalNo() {
		return "APR-%d-%04d".formatted(
			LocalDate.now(clock.withZone(KST)).getYear(), submissionRepository.nextApprovalSequence());
	}

	/**
	 * 노출 영역 (§API 2) — 전 위치 사각형을 감싸는 경계 사각형이다. 셀을 전개하지 않고 사각형의 min·max 만
	 * 접는 것으로 충분하다: 합집합의 경계는 원본 사각형들의 경계와 같다. 조회 시점 계산이라 저장하지 않는다.
	 * <p>
	 * 선행 조건은 "위치 1개 이상, 위치마다 사각형 1개 이상"이고 접수 검증(MSG-498 buildLocations·expand)이
	 * 그 둘을 강제한다 — 신청을 만드는 경로가 그 애그리거트 하나뿐이라 빈 입력이 여기 닿지 못한다.
	 * 그래서 빈 경우의 분기를 두지 않는다(응답 필드도 non-null 계약이다).
	 */
	private EventSubmissionAreaRectDto exposureRect(List<EventSubmissionLocation> locations) {
		int minGridY = Integer.MAX_VALUE;
		int maxGridY = Integer.MIN_VALUE;
		int minGridX = Integer.MAX_VALUE;
		int maxGridX = Integer.MIN_VALUE;
		for (EventSubmissionLocation location : locations) {
			for (EventSubmissionAreaRect rect : location.getRects()) {
				minGridY = Math.min(minGridY, rect.getMinGridY());
				maxGridY = Math.max(maxGridY, rect.getMaxGridY());
				minGridX = Math.min(minGridX, rect.getMinGridX());
				maxGridX = Math.max(maxGridX, rect.getMaxGridX());
			}
		}
		return new EventSubmissionAreaRectDto(minGridY, maxGridY, minGridX, maxGridX);
	}

	/** 클라이언트 문자열 → 상태 (MSG-499 parseStatus 선례). 조용한 기본값 폴백 없이 13455 로 거른다. */
	private EventSubmissionStatus parseStatus(String status) {
		try {
			return EventSubmissionStatus.valueOf(status.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_STATUS_FILTER);
		}
	}
}
