package com.msg.fillmap.user.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.AdminOrgAccountRequestDetailResponseDto;
import com.msg.fillmap.user.dto.AdminOrgAccountRequestListResponseDto;
import com.msg.fillmap.user.dto.OrgAccountIssueResponseDto;
import com.msg.fillmap.user.dto.OrgAccountRequestApproveRequestDto;
import com.msg.fillmap.user.dto.OrgAccountRequestCreateRequestDto;
import com.msg.fillmap.user.dto.OrgAccountRequestRejectRequestDto;
import com.msg.fillmap.user.entity.OrgAccountRequest;
import com.msg.fillmap.user.entity.OrgAccountRequestStatus;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.OrgAccountRequestRepository;

/**
 * 행사 운영자 계정 발급 요청 (MSG-499 FR-6) — 비로그인 공개 폼의 접수와 관리자 큐·상세·심사.
 *
 * <p>접수 값은 검증되지 않은 자기 신고이고 신뢰 경계는 관리자 심사다. 그래서 이 서비스는 형식 검증을
 * 통과한 값을 그대로 저장하고, 발급 여부는 승인 경로에서만 갈린다. 관리자 API 의 role 검사는
 * SecurityConfig 의 {@code /api/admin/**} matcher 가 하고 여기서 다시 판정하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class OrgAccountRequestService {

	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	private final OrgAccountRequestRepository orgAccountRequestRepository;
	private final OrgAccountIssueService orgAccountIssueService;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	/** 프로덕션 생성자 — clock 을 UTC 로 고정해 Lombok 전체 생성자에 위임한다 (OrgAccountService 선례). */
	@Autowired
	public OrgAccountRequestService(OrgAccountRequestRepository orgAccountRequestRepository,
		OrgAccountIssueService orgAccountIssueService, TransactionTemplate transactionTemplate) {
		this(orgAccountRequestRepository, orgAccountIssueService, transactionTemplate, Clock.systemUTC());
	}

	/**
	 * 공개 폼 접수 (API 1). 저장은 UPSERT 한 문장이라 같은 이메일의 재접수가 대기 행 하나로 수렴하고
	 * (더블클릭·오타 정정), 동시 접수 두 건도 부분 유니크 제약 위반 없이 끝난다. 최초 접수 시각은
	 * 보존되고 마지막 접수 시각만 갱신된다 — 그 값이 관리자 심사의 검토 기준 시각이 된다.
	 */
	@Transactional
	public void create(OrgAccountRequestCreateRequestDto request) {
		orgAccountRequestRepository.upsertPending(request.orgName(), request.contactName(),
			request.contactPhone(), request.email(), request.eventName(), request.content(),
			LocalDateTime.now(clock));
	}

	/**
	 * 요청 큐 (API 2). 상태 필터 기준 마지막 접수 최신순 오프셋 페이징이고, 화면 탭 뱃지용 건수 3종은
	 * 필터와 무관한 전체 집계다. 거부 판정은 처음 걸린 하나를 돌려준다: 상태 파싱 실패(1424) →
	 * 페이지 범위 밖(1425).
	 */
	@Transactional(readOnly = true)
	public AdminOrgAccountRequestListResponseDto getRequests(String status, int page, int size) {
		OrgAccountRequestStatus filter = parseStatus(status);
		// PageRequest.of 에 그냥 넘기면 IllegalArgumentException 이 catch-all 핸들러에서 500 이 된다.
		// 오프셋(page*size)이 int 를 넘는 극단 양수도 같다 (AdminReportServiceImpl 선례).
		if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE
			|| (long) page * size > Integer.MAX_VALUE) {
			throw new ApiException(UserErrorCode.INVALID_PAGE_RANGE);
		}
		return AdminOrgAccountRequestListResponseDto.of(
			orgAccountRequestRepository.findAllByStatusOrderByUpdatedAtDesc(filter, PageRequest.of(page, size)),
			orgAccountRequestRepository.countByStatus(OrgAccountRequestStatus.PENDING),
			orgAccountRequestRepository.countByStatus(OrgAccountRequestStatus.ISSUED),
			orgAccountRequestRepository.countByStatus(OrgAccountRequestStatus.REJECTED));
	}

	/** 요청 상세 (API 3). 관리자 전용이라 존재 은닉이 필요 없어 없는 요청은 그대로 1421 이다. */
	@Transactional(readOnly = true)
	public AdminOrgAccountRequestDetailResponseDto getRequest(Long requestId) {
		return AdminOrgAccountRequestDetailResponseDto.from(orgAccountRequestRepository.findById(requestId)
			.orElseThrow(() -> new ApiException(UserErrorCode.ORG_ACCOUNT_REQUEST_NOT_FOUND)));
	}

	/**
	 * 승인 — 계정 생성과 초기 비밀번호 발송 (API 4). 트랜잭션 안은 요청 행 잠금·검토 시점 대조·계정
	 * 생성·상태 전이까지이고, <b>메일 발송은 커밋 뒤</b>다 (경계를 눈에 보이게 두려고
	 * {@code @Transactional} 대신 TransactionTemplate 을 쓴다 — PasswordService 선례).
	 *
	 * <p>발송 실패는 응답의 emailSent 로만 드러나고 발급을 뒤집지 않는다. 커밋과 발송 사이에 프로세스가
	 * 죽으면 요청은 ISSUED 인데 메일이 안 나간 상태가 남는데(수용된 크래시 창), 그 복구 수칙은
	 * "응답을 못 받았으면 상세를 재조회하고 ISSUED 면 재발송 API 를 쓴다"이다.
	 */
	public OrgAccountIssueResponseDto approve(Long requestId, OrgAccountRequestApproveRequestDto request) {
		IssuedInitialPassword issued = transactionTemplate.execute(status -> {
			OrgAccountRequest target = lockPending(requestId, request.updatedAt());
			IssuedInitialPassword account = orgAccountIssueService.createAccount(target.getEmail(),
				target.getContactName(), target.getContactPhone(), target.getOrgName());
			target.issue(account.userId(), LocalDateTime.now(clock));
			return account;
		});
		return new OrgAccountIssueResponseDto(issued.userId(), orgAccountIssueService.sendInitialPassword(issued));
	}

	/**
	 * 반려 (API 5). <b>메일을 보내지 않는다</b> — 반려 통보는 당분간 수기이고 저장된 사유가 그 재료다.
	 * 검토 시점 대조를 승인과 똑같이 하는 이유는, 검토한 신청과 다른 내용을 그 사유로 반려하는 어긋남을
	 * 막기 위해서다.
	 */
	@Transactional
	public void reject(Long requestId, OrgAccountRequestRejectRequestDto request) {
		lockPending(requestId, request.updatedAt()).reject(request.reason(), LocalDateTime.now(clock));
	}

	/**
	 * 심사 진입부 — 행 잠금, 대기 상태 확인, 검토 시점 대조 (승인·반려 공통).
	 *
	 * <p>동시 심사의 늦은 쪽은 잠금 대기 후 바뀐 상태를 읽고 1422 로 걸린다. 잠금 획득 <b>뒤에</b>
	 * 마지막 접수 시각을 대조하는 것이 계약이다 — 잠금 전에 읽은 값으로 비교하면 대조와 처리 사이에
	 * 익명 재접수가 끼어들 수 있다. 비교는 값 완전 일치다(서버가 내려준 값을 그대로 받으므로).
	 */
	private OrgAccountRequest lockPending(Long requestId, LocalDateTime reviewedAt) {
		OrgAccountRequest request = orgAccountRequestRepository.findWithLockById(requestId)
			.orElseThrow(() -> new ApiException(UserErrorCode.ORG_ACCOUNT_REQUEST_NOT_FOUND));
		if (request.getStatus() != OrgAccountRequestStatus.PENDING) {
			throw new ApiException(UserErrorCode.ORG_ACCOUNT_REQUEST_ALREADY_PROCESSED);
		}
		if (!request.getUpdatedAt().equals(reviewedAt)) {
			throw new ApiException(UserErrorCode.ORG_ACCOUNT_REQUEST_MODIFIED);
		}
		return request;
	}

	/** 클라이언트 문자열 → 상태 (AdminReportServiceImpl.parseStatus 선례). 조용한 기본값 폴백 없이 1424 로 거른다. */
	private OrgAccountRequestStatus parseStatus(String status) {
		try {
			return OrgAccountRequestStatus.valueOf(status.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(UserErrorCode.INVALID_ORG_REQUEST_STATUS);
		}
	}
}
