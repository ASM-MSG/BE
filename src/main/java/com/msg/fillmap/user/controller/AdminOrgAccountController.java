package com.msg.fillmap.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.user.dto.AdminEmailChangeRequestListResponseDto;
import com.msg.fillmap.user.dto.AdminOrgAccountListResponseDto;
import com.msg.fillmap.user.dto.AdminOrgAccountRequestDetailResponseDto;
import com.msg.fillmap.user.dto.AdminOrgAccountRequestListResponseDto;
import com.msg.fillmap.user.dto.EmailChangeApproveRequestDto;
import com.msg.fillmap.user.dto.EmailChangeApproveResponseDto;
import com.msg.fillmap.user.dto.EmailChangeRejectRequestDto;
import com.msg.fillmap.user.dto.OrgAccountCreateRequestDto;
import com.msg.fillmap.user.dto.OrgAccountIssueResponseDto;
import com.msg.fillmap.user.dto.OrgAccountRequestApproveRequestDto;
import com.msg.fillmap.user.dto.OrgAccountRequestRejectRequestDto;
import com.msg.fillmap.user.dto.OrgAccountResendResponseDto;
import com.msg.fillmap.user.service.AdminEmailChangeRequestService;
import com.msg.fillmap.user.service.OrgAccountIssueService;
import com.msg.fillmap.user.service.OrgAccountRequestService;

/**
 * 관리자 행사 운영자 계정 API (MSG-499). 발급 요청 큐(조회·상세·승인·반려)와 계정 축(직접 발급·
 * 재발송·목록)을 한 컨트롤러에 둔다 — 한 화면의 재료라 분리 이득이 없다 (AdminReportController 선례).
 *
 * <p>ADMIN 검사는 여기에 없다. SecurityConfig 의 {@code /api/admin/**} matcher 가 필터 단계에서 거른다.
 */
@Tag(
	name = "관리자 행사 운영자 계정 (Admin Org Account)",
	description = "계정 발급 요청 검토와 계정 발급·초기 비밀번호 재발송 API (MSG-499), "
		+ "아이디 변경 요청 심사 (MSG-500). ADMIN 권한 필수."
)
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOrgAccountController {

	private final OrgAccountRequestService orgAccountRequestService;
	private final OrgAccountIssueService orgAccountIssueService;
	// 아이디 변경 요청 심사 (MSG-500) — 계정 축의 한 화면이라 컨트롤러를 새로 만들지 않는다.
	private final AdminEmailChangeRequestService adminEmailChangeRequestService;

	@Operation(
		summary = "계정 발급 요청 목록 조회",
		description = "상태 필터 기준으로 발급 요청을 마지막 접수 최신순 페이지 단위로 조회한다. 기본은 대기(PENDING) "
			+ "요청이다. 상태별 건수 3종이 필터와 무관하게 함께 실려 탭 뱃지를 그릴 수 있다.\n\n"
			+ "지원하지 않는 status 는 400(1424), page 음수나 size 범위(1~100) 밖은 400(1425) 이다."
	)
	@GetMapping("/org-account-requests")
	public SuccessResponse<AdminOrgAccountRequestListResponseDto> getRequests(
		@Parameter(description = "처리 상태 필터 (PENDING, ISSUED, REJECTED — 대소문자 무관)", example = "PENDING")
		@RequestParam(defaultValue = "PENDING") String status,

		@Parameter(description = "페이지 번호 (0부터)", example = "0")
		@RequestParam(defaultValue = "0") int page,

		@Parameter(description = "페이지 크기 (1~100)", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		return SuccessResponse.of(orgAccountRequestService.getRequests(status, page, size));
	}

	@Operation(
		summary = "계정 발급 요청 상세 조회",
		description = "접수 필드 전체와 처리 결과를 조회한다. 응답의 updatedAt 은 승인·반려 요청에 그대로 되돌려 "
			+ "보내야 하는 검토 기준 시각이다 — 검토와 처리 사이에 신청 내용이 바뀌면 그 값으로 걸러진다.\n\n"
			+ "없는 요청은 404(1421) 다."
	)
	@GetMapping("/org-account-requests/{requestId}")
	public SuccessResponse<AdminOrgAccountRequestDetailResponseDto> getRequest(
		@Parameter(description = "조회할 요청 id", example = "7") @PathVariable Long requestId
	) {
		return SuccessResponse.of(orgAccountRequestService.getRequest(requestId));
	}

	@Operation(
		summary = "계정 발급 요청 승인",
		description = "행사 운영자 계정을 만들고 초기 비밀번호를 공식 이메일로 발송한다. 응답에는 발송 성공 여부만 "
			+ "실리고 초기 비밀번호 평문은 어디에도 실리지 않는다.\n\n"
			+ "메일 발송이 실패해도 계정과 발급됨 상태는 유지되며 emailSent 가 false 로 온다 — 복구는 재발송 API 다. "
			+ "응답 자체를 받지 못했으면 상세를 재조회해 ISSUED 인지 확인하고, 발송 확신이 없으면 재발송을 쓴다.\n\n"
			+ "없는 요청은 404(1421), 이미 처리된 요청과 동시 승인의 늦은 쪽은 409(1422), 검토 이후 요청 내용이 "
			+ "바뀌었으면 409(1426), 이미 계정이 있는 이메일은 409(1409) 다."
	)
	@PostMapping("/org-account-requests/{requestId}/approve")
	public SuccessResponse<OrgAccountIssueResponseDto> approve(
		@Parameter(description = "승인할 요청 id", example = "7") @PathVariable Long requestId,
		@Valid @RequestBody OrgAccountRequestApproveRequestDto request
	) {
		return SuccessResponse.of(orgAccountRequestService.approve(requestId, request));
	}

	@Operation(
		summary = "계정 발급 요청 반려",
		description = "요청을 반려하고 사유를 저장한다. 사유는 필수이며 <b>메일은 발송되지 않는다</b> — 반려 통보는 "
			+ "당분간 수기이고 저장된 사유가 그 재료다.\n\n"
			+ "없는 요청은 404(1421), 이미 처리된 요청은 409(1422), 검토 이후 요청 내용이 바뀌었으면 409(1426) 다."
	)
	@PostMapping("/org-account-requests/{requestId}/reject")
	public SuccessResponse<Void> reject(
		@Parameter(description = "반려할 요청 id", example = "7") @PathVariable Long requestId,
		@Valid @RequestBody OrgAccountRequestRejectRequestDto request
	) {
		orgAccountRequestService.reject(requestId, request);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "행사 운영자 계정 직접 발급",
		description = "공문으로 먼저 확인된 기관에 발급 요청 없이 계정을 만들고 초기 비밀번호를 발송한다. 결과는 "
			+ "승인과 같다.\n\n"
			+ "응답을 받지 못했으면 같은 요청을 재시도한다. 1409(이미 존재)가 오면 그 이메일로 계정이 있다는 뜻일 "
			+ "뿐 발급 성공의 증거가 아니므로, 계정 목록의 email 검색으로 가른다 — 결과가 있으면 발급된 것이고, "
			+ "없으면 다른 계정과의 이메일 충돌이라 기관에 다른 공식 이메일을 요청한다.\n\n"
			+ "이미 계정이 있는 이메일은 409(1409) 다."
	)
	@PostMapping("/organizations")
	public SuccessResponse<OrgAccountIssueResponseDto> issueDirect(
		@Valid @RequestBody OrgAccountCreateRequestDto request
	) {
		return SuccessResponse.of(orgAccountIssueService.issueDirect(request));
	}

	@Operation(
		summary = "초기 비밀번호 재발송",
		description = "새 초기 비밀번호를 만들어 공식 이메일로 다시 보낸다 — <b>재발송은 재발급이다.</b> 평문을 "
			+ "저장하지 않아 보냈던 비밀번호를 다시 보낼 수 없고, 이전 초기 비밀번호는 즉시 무효가 된다.\n\n"
			+ "대상은 아직 초기 로그인을 마치지 않은 행사 운영자 계정뿐이다. 이미 본인이 비밀번호를 바꾼 계정은 "
			+ "409(1423) 이며, 그 경우의 분실 복구는 비밀번호 재설정 흐름을 안내한다.\n\n"
			+ "없는 사용자는 404(1404) 다."
	)
	@PostMapping("/organizations/{userId}/resend-password")
	public SuccessResponse<OrgAccountResendResponseDto> resendPassword(
		@Parameter(description = "재발송 대상 계정 id", example = "42") @PathVariable Long userId
	) {
		return SuccessResponse.of(orgAccountIssueService.resendInitialPassword(userId));
	}

	@Operation(
		summary = "발급된 행사 운영자 계정 목록",
		description = "발급 최신순으로 계정을 조회한다. 목록은 이 발급 경로가 만드는 형태(역할 ORG · 제공자 LOCAL) "
			+ "만 담는다 — 재발송 대상 식별과 직접 발급 복구 확인이 목적이라서다.\n\n"
			+ "각 항목의 mustChange 가 화면의 사용 중 / 초기 로그인 전 라벨이다(false 가 사용 중). "
			+ "email 을 주면 완전 일치 검색이고, page 음수나 size 범위(1~100) 밖은 400(1425) 이다."
	)
	@GetMapping("/organizations")
	public SuccessResponse<AdminOrgAccountListResponseDto> getAccounts(
		@Parameter(description = "페이지 번호 (0부터)", example = "0")
		@RequestParam(defaultValue = "0") int page,

		@Parameter(description = "페이지 크기 (1~100)", example = "20")
		@RequestParam(defaultValue = "20") int size,

		@Parameter(description = "공식 이메일 완전 일치 필터 (선택)", example = "event@busanjin.go.kr")
		@RequestParam(required = false) String email
	) {
		return SuccessResponse.of(orgAccountIssueService.getAccounts(page, size, email));
	}

	@Operation(
		summary = "아이디 변경 요청 목록 조회",
		description = "행사 운영자가 낸 아이디(공식 이메일) 변경 요청을 상태 필터 기준으로 접수 최신순 조회한다. "
			+ "기본은 대기(PENDING) 요청이다. 항목에 현재 아이디와 바꾸려는 이메일이 나란히 실려 그대로 대조할 "
			+ "수 있고, 상태별 건수 3종이 필터와 무관하게 함께 온다.\n\n"
			+ "응답의 createdAt 은 승인·반려 요청에 되돌려 보내야 하는 검토 기준 시각이다 — 재요청은 같은 대기 "
			+ "행을 덮어쓰므로, 이 값으로 걸러야 본 적 없는 이메일을 승인하는 사고가 없다.\n\n"
			+ "지원하지 않는 status 는 400(1424), page 음수나 size 범위(1~100) 밖은 400(1425) 이다."
	)
	@GetMapping("/email-change-requests")
	public SuccessResponse<AdminEmailChangeRequestListResponseDto> getEmailChangeRequests(
		@Parameter(description = "처리 상태 필터 (PENDING, APPROVED, REJECTED — 대소문자 무관)", example = "PENDING")
		@RequestParam(defaultValue = "PENDING") String status,

		@Parameter(description = "페이지 번호 (0부터)", example = "0")
		@RequestParam(defaultValue = "0") int page,

		@Parameter(description = "페이지 크기 (1~100)", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		return SuccessResponse.of(adminEmailChangeRequestService.getRequests(status, page, size));
	}

	@Operation(
		summary = "아이디 변경 요청 승인",
		description = "요청한 이메일로 로그인 아이디를 교체하고 <b>새 이메일로 변경 완료를 통지</b>한다. 요청 전이와 "
			+ "이메일 교체는 한 트랜잭션이라 함께 성공하거나 함께 실패한다. 비밀번호와 세션은 그대로이며 다음 "
			+ "로그인부터 새 아이디를 쓴다.\n\n"
			+ "발급·반려 통보와 달리 메일을 보내는 이유는 로그인 수단 자체가 바뀌는 사건이라서다 — 알리지 않으면 "
			+ "행사 운영자가 계정 접근을 잃는다. 발송이 실패해도 교체는 유지되며 emailSent 가 false 로 온다.\n\n"
			+ "없는 요청은 404(1427), 이미 처리된 요청은 409(1428), 검토 이후 재요청으로 내용이 바뀌었으면 "
			+ "409(1429), 요청한 이메일이 이미 다른 계정에 있으면 409(1409) 다."
	)
	@PostMapping("/email-change-requests/{requestId}/approve")
	public SuccessResponse<EmailChangeApproveResponseDto> approveEmailChange(
		@Parameter(description = "승인할 요청 id", example = "3") @PathVariable Long requestId,
		@Valid @RequestBody EmailChangeApproveRequestDto request
	) {
		return SuccessResponse.of(adminEmailChangeRequestService.approve(requestId, request));
	}

	@Operation(
		summary = "아이디 변경 요청 반려",
		description = "요청을 반려하고 사유를 저장한다. 아이디는 바뀌지 않고 <b>메일도 발송되지 않는다</b> — 반려 "
			+ "통보는 수기이고 저장된 사유가 그 재료다. 처리 후에는 같은 계정이 다시 접수할 수 있다.\n\n"
			+ "검토 기준 시각을 승인과 똑같이 요구하는 것은, 검토한 내용과 다른 요청을 그 사유로 반려하는 어긋남을 "
			+ "막기 위해서다.\n\n"
			+ "없는 요청은 404(1427), 이미 처리된 요청은 409(1428), 검토 이후 내용이 바뀌었으면 409(1429) 다."
	)
	@PostMapping("/email-change-requests/{requestId}/reject")
	public SuccessResponse<Void> rejectEmailChange(
		@Parameter(description = "반려할 요청 id", example = "3") @PathVariable Long requestId,
		@Valid @RequestBody EmailChangeRejectRequestDto request
	) {
		adminEmailChangeRequestService.reject(requestId, request);
		return new SuccessResponse<>(null);
	}
}
