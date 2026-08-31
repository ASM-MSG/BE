package com.msg.fillmap.user.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.dto.AdminEmailChangeRequestListResponseDto;
import com.msg.fillmap.user.dto.EmailChangeApproveRequestDto;
import com.msg.fillmap.user.dto.EmailChangeApproveResponseDto;
import com.msg.fillmap.user.dto.EmailChangeRejectRequestDto;
import com.msg.fillmap.user.entity.OrgEmailChangeRequest;
import com.msg.fillmap.user.entity.OrgEmailChangeStatus;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.OrgEmailChangeRequestRepository;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 아이디(공식 이메일) 변경 요청 심사 (MSG-500 §API 7, D-13). MSG-497 이 접수까지 만들어 둔 요청이 어느
 * 화면에도 안 걸려 있던 것을 이 서비스가 닫는다 — 계정 관련 심사라 event 가 아니라 user 도메인이고,
 * 에러 대역도 1xxx 다.
 *
 * <p>승인은 두 갱신이 한 트랜잭션이다: 요청을 PENDING 에서 APPROVED 로 전이하고 같은 트랜잭션에서
 * {@code users.email} 을 교체한다. 둘 다 <b>벌크 UPDATE</b> 라 순서가 코드 순서 그대로이고, 엔티티를
 * 로드해 더티 체킹으로 바꾸지 않는다(전 컬럼 UPDATE 가 다른 갱신을 되덮는 것을 막는다 — D-13).
 *
 * <p>통지 메일은 <b>커밋 뒤</b> 새 이메일로 보낸다. 안에 두면 SES 왕복이 커넥션을 붙들고, 발송 성공 뒤
 * 커밋이 실패하면 "바뀌었다고 통지했는데 아직 옛 아이디"인 역방향 구멍이 생긴다.
 */
@Slf4j
@Service
public class AdminEmailChangeRequestService {

	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	private static final String MAIL_SUBJECT = "[필맵] 로그인 아이디 변경 완료 안내";
	private static final String MAIL_BODY_FORMAT = """
		요청하신 로그인 아이디(이메일) 변경이 승인되었습니다.

		새 아이디: %s

		다음 로그인부터 이 주소를 사용해 주세요. 비밀번호는 그대로입니다.""";

	private final OrgEmailChangeRequestRepository requestRepository;
	private final UserRepository userRepository;
	private final MailSender mailSender;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	/** 프로덕션 생성자 — clock 을 UTC 로 고정해 전체 생성자로 위임한다 (OrgAccountRequestService 선례). */
	@Autowired
	public AdminEmailChangeRequestService(OrgEmailChangeRequestRepository requestRepository,
		UserRepository userRepository, MailSender mailSender, TransactionTemplate transactionTemplate) {
		this(requestRepository, userRepository, mailSender, transactionTemplate, Clock.systemUTC());
	}

	public AdminEmailChangeRequestService(OrgEmailChangeRequestRepository requestRepository,
		UserRepository userRepository, MailSender mailSender, TransactionTemplate transactionTemplate, Clock clock) {
		this.requestRepository = requestRepository;
		this.userRepository = userRepository;
		this.mailSender = mailSender;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
	}

	/**
	 * 요청 큐 (§API 7). 상태 필터 기준 접수 최신순이고 건수 3종은 필터와 무관한 전체 집계다. 거부 판정은
	 * 발급 요청 큐와 같은 코드다 — 같은 도메인·같은 판정이라 상수를 새로 만들지 않는다(1424 → 1425 순서).
	 */
	@Transactional(readOnly = true)
	public AdminEmailChangeRequestListResponseDto getRequests(String status, int page, int size) {
		OrgEmailChangeStatus filter = parseStatus(status);
		// PageRequest.of 에 그냥 넘기면 IllegalArgumentException 이 catch-all 핸들러에서 500 이 된다
		// (AdminReportServiceImpl 선례). 오프셋이 int 를 넘는 극단 양수도 같다.
		if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE
			|| (long) page * size > Integer.MAX_VALUE) {
			throw new ApiException(UserErrorCode.INVALID_PAGE_RANGE);
		}
		return AdminEmailChangeRequestListResponseDto.of(
			requestRepository.findAdminPageByStatus(filter, PageRequest.of(page, size)),
			requestRepository.countByStatus(OrgEmailChangeStatus.PENDING),
			requestRepository.countByStatus(OrgEmailChangeStatus.APPROVED),
			requestRepository.countByStatus(OrgEmailChangeStatus.REJECTED));
	}

	/**
	 * 승인 (§API 7, D-13). 실행 순서가 계약이다.
	 * <p>
	 * ① 요청을 읽어 상태와 검토 시각을 먼저 본다(빠른 거절, 값도 여기서 챙긴다). ② 대상 계정 행을 비관
	 * 잠금으로 잡는다 — 계정 삭제와 <b>잠금 순서를 맞추기 위한 것</b>이고 근거는 아래
	 * {@code findWithLockById} 호출부 주석에 있다. ③ 조건부 UPDATE 로 전이한다. 술어에 접수 시각이 들어
	 * 있어 검토 이후 재제출된 요청은 여기서 걸린다. ④ 0행이면 재조회로 가른다(1427·1428·1429).
	 * ⑤ 새 이메일이 다른 계정에 있는지 선검사한다 — 읽히는 1409 를 주기 위한 것이고 <b>경합에는 진다</b>
	 * (서로 다른 두 요청이 같은 이메일을 노리면 둘 다 통과한다). ⑥ 같은 트랜잭션에서 이메일을 교체한다 —
	 * 벌크 UPDATE 라 uq_users_email 위반이 <b>실행 즉시</b> 뜨므로 그 자리에서 1409 로 번역한다(커밋
	 * 시점까지 미뤄지면 잡을 자리가 없다). ⑦ 커밋 후 새 이메일로 통지한다.
	 * <p>
	 * <b>선검사가 전이보다 뒤인 것이 계약이다</b> (Codex 스톱 게이트 적발). 앞에 두면 같은 요청의 동시
	 * 재승인에서 늦은 쪽이 1409 로 잘못 수렴한다 — 아래 호출부 주석에 상세가 있다. 순서를 되돌리면
	 * {@code EmailChangeApprovalConcurrencyTest} 의 동시 재승인 케이스가 1428 대신 1409 를 받아 실패한다.
	 */
	public EmailChangeApproveResponseDto approve(Long requestId, EmailChangeApproveRequestDto request) {
		Approved approved = transactionTemplate.execute(status -> {
			OrgEmailChangeRequest target = findReviewable(requestId, request.requestedAt());
			Long userId = target.getUserId();
			String newEmail = target.getRequestedEmail();
			// 잠금 순서를 users → 요청 행으로 통일한다. 계정 삭제(UserService)는 users 행을 지우고 FK
			// ON DELETE CASCADE(V46)가 그 사용자의 요청 행을 잠그므로, 여기서 요청 행을 먼저 잠그면
			// 반대 순서가 되어 동시 실행이 AB-BA 데드락(한쪽 500)으로 끝난다. 읽기만 하고 버리는
			// 조회지만 잡는 잠금이 목적이고, 그 사이 탈퇴한 계정은 여기서 1404 로 갈린다.
			// 이 조회가 영속성 컨텍스트에 올린 User 는 <b>아무도 다시 쓰지 않는다</b> — 값은 위에서 이미
			// 챙겼고, 바로 아래 두 벌크 UPDATE 의 clearAutomatically 가 컨텍스트를 비워 스테일 재사용
			// 경로 자체가 없다(수정한 적이 없으니 비워질 때 나갈 flush 도 없다).
			userRepository.findWithLockById(userId)
				.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));

			LocalDateTime now = LocalDateTime.now(clock);
			if (requestRepository.approvePending(requestId, request.requestedAt(), now) == 0) {
				throw transitionFailure(requestId, request.requestedAt());
			}
			// 이메일 선검사가 전이 <b>뒤</b>인 것이 계약이다. 앞에 두면 같은 요청의 동시 재승인에서 늦은
			// 쪽이 잠금 대기 후 이긴 쪽이 이미 적용한 그 이메일을 발견해 1409 로 끝난다 — 관리자에게
			// "다른 계정과 충돌"이라는 거짓 원인을 보여준다. 전이가 먼저면 늦은 쪽은 0행 → 1428 로
			// 결정적으로 수렴하고, 진짜 충돌(다른 계정 선점)은 여기서 1409 로 잡히며 전이는 롤백된다.
			if (userRepository.existsByEmail(newEmail)) {
				throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS);
			}
			replaceEmail(userId, newEmail);
			return new Approved(userId, newEmail);
		});
		return new EmailChangeApproveResponseDto(requestId, approved.email(), notify(approved.email()));
	}

	/**
	 * 반려 (§API 7). 승인과 같은 검토 시점 가드를 쓰고 사유를 요청 행에 남긴다. 이메일은 그대로다.
	 * <b>메일을 보내지 않는다</b> — 반려 통보는 수기이고 저장된 사유가 그 재료다(MSG-499 반려 선례).
	 */
	@Transactional
	public void reject(Long requestId, EmailChangeRejectRequestDto request) {
		findReviewable(requestId, request.requestedAt());
		if (requestRepository.rejectPending(requestId, request.requestedAt(), request.reason(),
			LocalDateTime.now(clock)) == 0) {
			throw transitionFailure(requestId, request.requestedAt());
		}
	}

	/**
	 * 이메일 교체 (D-13) — 단일 컬럼 UPDATE 다. 영향 행 0은 그 사이 탈퇴한 계정이고, 유니크 위반은 같은
	 * 이메일을 노린 다른 승인이 먼저 커밋한 경우라 선검사와 같은 1409 로 수렴시킨다.
	 */
	private void replaceEmail(Long userId, String newEmail) {
		try {
			if (userRepository.updateEmail(userId, newEmail) == 0) {
				throw new ApiException(UserErrorCode.USER_NOT_FOUND);
			}
		} catch (DataIntegrityViolationException e) {
			throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS, e);
		}
	}

	/** 통지 — 실패는 삼키고 false 로 알린다. 아이디는 이미 바뀌었고 발송 실패가 그것을 되돌릴 이유가 아니다. */
	private boolean notify(String newEmail) {
		try {
			mailSender.send(newEmail, MAIL_SUBJECT, MAIL_BODY_FORMAT.formatted(newEmail));
			return true;
		} catch (RuntimeException e) {
			log.error("아이디 변경 통지 발송 실패 — 교체는 유지된다: to={}", newEmail, e);
			return false;
		}
	}

	/** 심사 대상 조회 — 없으면 1427, 대기가 아니면 1428, 검토 이후 재제출됐으면 1429 (전이 전 빠른 거절). */
	private OrgEmailChangeRequest findReviewable(Long requestId, LocalDateTime reviewedAt) {
		OrgEmailChangeRequest request = requestRepository.findById(requestId)
			.orElseThrow(() -> new ApiException(UserErrorCode.EMAIL_CHANGE_REQUEST_NOT_FOUND));
		if (request.getStatus() != OrgEmailChangeStatus.PENDING) {
			throw new ApiException(UserErrorCode.EMAIL_CHANGE_REQUEST_ALREADY_PROCESSED);
		}
		if (!request.getCreatedAt().equals(reviewedAt)) {
			throw new ApiException(UserErrorCode.EMAIL_CHANGE_REQUEST_MODIFIED);
		}
		return request;
	}

	/** 조건부 UPDATE 가 0행일 때의 이유 — 빠른 거절 뒤에 끼어든 동시 처리·재제출을 여기서 다시 가른다. */
	private ApiException transitionFailure(Long requestId, LocalDateTime reviewedAt) {
		OrgEmailChangeRequest request = requestRepository.findById(requestId)
			.orElseThrow(() -> new ApiException(UserErrorCode.EMAIL_CHANGE_REQUEST_NOT_FOUND));
		if (request.getStatus() != OrgEmailChangeStatus.PENDING) {
			return new ApiException(UserErrorCode.EMAIL_CHANGE_REQUEST_ALREADY_PROCESSED);
		}
		return new ApiException(UserErrorCode.EMAIL_CHANGE_REQUEST_MODIFIED);
	}

	/** 클라이언트 문자열 → 상태 (발급 요청 큐 parseStatus 선례). 조용한 기본값 폴백 없이 1424 로 거른다. */
	private OrgEmailChangeStatus parseStatus(String status) {
		try {
			return OrgEmailChangeStatus.valueOf(status.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(UserErrorCode.INVALID_ORG_REQUEST_STATUS);
		}
	}

	/** 커밋 뒤 발송에 필요한 값만 트랜잭션 밖으로 들고 나온다 — 엔티티를 밖으로 흘리지 않는다. */
	private record Approved(Long userId, String email) {
	}
}
