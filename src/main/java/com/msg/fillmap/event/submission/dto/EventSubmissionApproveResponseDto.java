package com.msg.fillmap.event.submission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 승인 결과 (MSG-500 §API 3). 승인 입력이 전부 저장된 신청에서 나오므로 요청 본문이 없고, 응답도 승인
 * 번호와 전이 결과만 담는다 — 산출물(미션 id)은 관리자 화면이 쓰지 않는 내부 링크라 싣지 않는다.
 */
@Schema(description = "행사 등재 신청 승인 결과",
	requiredProperties = {"submissionId", "approvalNo", "status"})
public record EventSubmissionApproveResponseDto(
	@Schema(description = "승인한 신청 id", example = "7")
	Long submissionId,

	@Schema(description = "부여된 승인 번호", example = "APR-2026-0001")
	String approvalNo,

	@Schema(description = "전이 후 상태", example = "APPROVED")
	String status
) {
}
