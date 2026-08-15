package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 위치정보 사용 동의 변경 요청 (MSG-402). 온보딩 동의 제출과 프로필 편집 토글이 같은 요청을 쓴다.
 * 원시 boolean 이 아니라 Boolean 인 이유는 필드를 빼먹은 요청이 false 로 둔갑하지 않게 하기 위해서다
 * — 누락은 @NotNull 로 400 이다.
 */
@Schema(description = "위치정보 사용 동의 변경 요청")
public record LocationConsentUpdateRequestDto(
	@Schema(description = "true 면 동의, false 면 철회", example = "true")
	@NotNull(message = "동의 여부는 필수 항목입니다")
	Boolean consented
) {
}
