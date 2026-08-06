package com.msg.fillmap.moderation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.moderation.dto.AdminReportListResponseDto;
import com.msg.fillmap.moderation.dto.AdminReportProcessResponseDto;
import com.msg.fillmap.moderation.service.AdminReportService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 관리자 신고 처리 API (MSG-195). 신고 축(목록·승인·기각)과 영상 축(블라인드 해제·단건 확인)을 한
 * 컨트롤러에 둔다 — 엔드포인트가 다섯이라 분리 이득이 없다 (§D1).
 *
 * <p>ADMIN 검사는 여기에 없다. SecurityConfig 의 /api/admin/** matcher 가 필터 단계에서 거르므로
 * 메서드마다 role 을 다시 확인하지 않는다 — 새 관리자 엔드포인트는 이 프리픽스 아래 두기만 하면 된다.
 */
@Tag(
	name = "관리자 신고 처리 (Admin Report)",
	description = "접수된 영상 신고의 열람·승인·기각과 블라인드 해제·단건 확인 API (MSG-195). ADMIN 권한 필수."
)
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminReportController {

	private final AdminReportService adminReportService;

	@Operation(
		summary = "신고 목록 조회",
		description = "상태 필터 기준으로 신고를 접수 최신순 페이지 단위로 조회한다. 기본은 미처리(PENDING) 신고다. "
			+ "항목에 신고자·영상 소유자 닉네임과 영상 현재 상태가 함께 담겨 목록만으로 판단할 수 있다. "
			+ "지원하지 않는 status 는 400(11420), page 음수나 size 범위(1~100) 밖은 400(11421) 이다. "
			+ "REVIEWING 은 유효한 값이지만 만드는 경로가 없어 항상 빈 목록이다."
	)
	@GetMapping("/reports")
	public SuccessResponse<AdminReportListResponseDto> getReports(
		@Parameter(description = "신고 상태 필터 (PENDING, REVIEWING, RESOLVED, REJECTED — 대소문자 무관)",
			example = "PENDING")
		@RequestParam(defaultValue = "PENDING") String status,

		@Parameter(description = "페이지 번호 (0부터)", example = "0")
		@RequestParam(defaultValue = "0") int page,

		@Parameter(description = "페이지 크기 (1~100)", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		return SuccessResponse.of(adminReportService.getReports(status, page, size));
	}

	@Operation(
		summary = "신고 승인",
		description = "신고를 RESOLVED 로 종결하고 대상 영상을 블라인드한다 — 한 트랜잭션이다. 영상이 이미 "
			+ "BLINDED 거나 DELETED 면 영상 전이 없이 신고만 종결하며, 응답의 videoStatus 로 구분할 수 있다. "
			+ "없는 신고는 404(11404), 이미 처리된 신고와 동시 처리의 늦은 쪽은 409(11410) 다."
	)
	@PostMapping("/reports/{reportId}/approve")
	public SuccessResponse<AdminReportProcessResponseDto> approve(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "승인할 신고 ID", example = "7") @PathVariable Long reportId
	) {
		return SuccessResponse.of(adminReportService.approve(principal.userId(), reportId));
	}

	@Operation(
		summary = "신고 기각",
		description = "신고를 REJECTED 로 종결한다. 영상에는 아무 영향이 없고 응답의 videoStatus 는 현재 상태 "
			+ "그대로다. 없는 신고는 404(11404), 이미 처리된 신고는 409(11410) 다."
	)
	@PostMapping("/reports/{reportId}/reject")
	public SuccessResponse<AdminReportProcessResponseDto> reject(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "기각할 신고 ID", example = "7") @PathVariable Long reportId
	) {
		return SuccessResponse.of(adminReportService.reject(principal.userId(), reportId));
	}
}
