package com.msg.fillmap.event.submission.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 노출 중지 결과 (MSG-500 §API 6). {@code emailSent} 가 false 여도 중지는 유지된다 — 발송 실패가 중지를
 * 뒤집지 않고, 저장된 사유가 수기 재통지의 재료다(재발송 API 없음, MSG-499 발급 발송 선례와 같은 결).
 */
@Schema(description = "행사 노출 중지 결과", requiredProperties = {"submissionId", "unpublishedAt", "emailSent"})
public record AdminEventUnpublishResponseDto(
	@Schema(description = "중지한 승인 행사 식별자 (= 신청 id)", example = "7")
	Long submissionId,

	@Schema(description = "중지 시각 (UTC)", example = "2026-08-30T02:11:00Z")
	LocalDateTime unpublishedAt,

	@Schema(description = "사유 통지 메일 발송 성공 여부 — false 여도 중지는 유지된다", example = "true")
	boolean emailSent
) {
}
