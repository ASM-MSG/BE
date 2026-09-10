package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 아이디 변경 요청 반려 (MSG-500 §API 7). 검토 기준 시각을 승인과 똑같이 요구하는 이유는, 검토한 내용과
 * 다른 요청을 그 사유로 반려하는 어긋남을 막기 위해서다(재제출로 이메일이 바뀐 뒤의 낡은 사유).
 * 사유는 필수이고 <b>메일은 나가지 않는다</b> — 반려 통보는 수기이고 저장된 사유가 그 재료다.
 */
@Schema(description = "아이디 변경 요청 반려", requiredProperties = {"requestedAt", "reason"})
public record EmailChangeRejectRequestDto(
	@Schema(description = "검토한 요청의 접수 시각 (목록의 createdAt 을 그대로)", example = "2026-08-28T02:00:00Z")
	@NotNull LocalDateTime requestedAt,

	@Schema(description = "반려 사유", example = "기관 도메인이 아닌 이메일이라 반려합니다")
	@NotBlank String reason
) {
}
