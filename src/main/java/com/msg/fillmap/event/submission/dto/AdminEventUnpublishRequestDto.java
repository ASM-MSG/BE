package com.msg.fillmap.event.submission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * 노출 중지 요청 (MSG-500 §API 6, FR-20). 사유가 필수인 것은 그 값이 <b>행사 운영자에게 나가는 통지 본문</b>
 * 이자, 재발송 API 를 만들지 않는 대신 남기는 수기 재통지의 재료이기 때문이다.
 */
@Schema(description = "행사 노출 중지 요청", requiredProperties = {"reason"})
public record AdminEventUnpublishRequestDto(
	@Schema(description = "중지 사유 — 행사 운영자에게 그대로 발송된다",
		example = "행사가 취소되어 노출을 중지합니다")
	@NotBlank String reason
) {
}
