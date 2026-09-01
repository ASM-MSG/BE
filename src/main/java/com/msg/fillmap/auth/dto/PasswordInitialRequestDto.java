package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 첫 로그인 초기 비밀번호 설정 (MSG-537 FR-AUTH-17). 현재 비밀번호를 받지 않는 것이 변경
 * ({@link PasswordChangeRequestDto})과의 유일한 차이다 — 초기 비밀번호로 이미 로그인에 성공한
 * 사용자만 도달하는 경로라 재입력을 요구하지 않는다. 새 비밀번호 정책은 변경과 동일하다.
 */
@Schema(description = "초기 비밀번호 설정 요청")
public record PasswordInitialRequestDto(
	@Schema(description = "새 비밀번호. 영문과 숫자를 각각 하나 이상 포함한 8~64자", example = "Fillmap1234")
	@NotBlank(message = "비밀번호는 필수 항목입니다")
	@Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하이어야 합니다")
	@Pattern(
		regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
		message = "비밀번호는 영문과 숫자를 각각 하나 이상 포함해야 합니다"
	)
	String newPassword
) {
}
