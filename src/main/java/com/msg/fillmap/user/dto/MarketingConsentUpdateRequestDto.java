package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 마케팅 정보 수신 동의 변경 요청 (MSG-433). LocationConsentUpdateRequestDto 미러다 — 원시 boolean 이
 * 아니라 Boolean 인 이유는 필드를 빼먹은 요청이 false 로 둔갑하지 않게 하기 위해서다.
 */
@Schema(description = "마케팅 정보 수신 동의 변경 요청")
public record MarketingConsentUpdateRequestDto(
	@Schema(description = "true 면 동의, false 면 철회", example = "true")
	@NotNull(message = "동의 여부는 필수 항목입니다")
	Boolean consented
) {
}
