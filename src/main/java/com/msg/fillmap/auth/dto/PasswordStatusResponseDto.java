package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 첫 로그인 비밀번호 강제 변경 상태 (MSG-497 FR-21). 로그인 직후 화면이 이 값으로 변경 화면 진입
 * 여부를 가른다. 비밀번호가 없는 소셜 계정은 플래그를 세울 경로가 없어 항상 false 다.
 */
@Schema(description = "비밀번호 강제 변경 상태", requiredProperties = {"mustChange"})
public record PasswordStatusResponseDto(
	@Schema(description = "true 면 비밀번호를 바꾸기 전까지 행사 등재 콘솔이 막힌다", example = "true")
	boolean mustChange
) {
}
