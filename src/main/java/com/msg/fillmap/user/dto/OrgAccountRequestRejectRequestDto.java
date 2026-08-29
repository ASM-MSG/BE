package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 계정 발급 요청 반려 (MSG-499 API 5). 사유는 필수이며 메일로 나가지 않는다 — 반려 통보는 당분간
 * 수기이고, 저장된 사유가 그 통보의 재료다. updatedAt 에코는 승인과 같은 이유다(검토한 신청과 다른
 * 내용을 그 사유로 반려하는 어긋남을 막는다).
 */
@Schema(description = "계정 발급 요청 반려 요청")
public record OrgAccountRequestRejectRequestDto(
	@Schema(description = "반려 사유 (최대 500자). 관리자가 신청자에게 수기로 통보할 때 쓴다", example = "기관 확인 서류가 누락되었습니다")
	@NotBlank(message = "반려 사유는 필수 항목입니다")
	@Size(max = 500, message = "반려 사유는 500자 이하이어야 합니다")
	String reason,

	@Schema(description = "상세 조회로 받은 마지막 접수 시각. 값이 다르면 검토 이후 요청이 바뀐 것이라 반려가 거부된다")
	@NotNull(message = "검토 기준 시각은 필수 항목입니다")
	LocalDateTime updatedAt
) {
}
