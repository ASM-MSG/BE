package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 위치정보 사용 동의 켜기 요청 (MSG-402). 온보딩 동의 제출과 프로필 화면이 같은 요청을 쓴다.
 * 2026-08-19 팀 합의로 이 동의는 철회가 불가해져(FR-USER-14 개정, MSG-433 §D-11) 유효한 값은
 * true 하나이며 false 는 1400 으로 거절된다 — false 가 형식상 유효값이라 이 DTO 가 아니라 서비스가
 * 거른다. 원시 boolean 이 아니라 Boolean 인 이유는 필드를 빼먹은 요청이 false 로 둔갑하지 않게 하기
 * 위해서다 — 누락은 @NotNull 로 400 이다.
 */
@Schema(description = "위치정보 사용 동의 켜기 요청")
public record LocationConsentUpdateRequestDto(
	@Schema(description = "true 면 동의. 이 동의는 철회할 수 없어 false 는 1400 으로 거절된다", example = "true")
	@NotNull(message = "동의 여부는 필수 항목입니다")
	Boolean consented
) {
}
