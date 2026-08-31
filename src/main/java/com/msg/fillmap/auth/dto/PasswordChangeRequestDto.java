package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 로그인 상태 비밀번호 변경 (MSG-497 FR-22). 새 비밀번호 정책은 회원가입과 동일하다
 * (SignupRequestDto.password 와 같은 제약).
 */
@Schema(description = "비밀번호 변경 요청")
public record PasswordChangeRequestDto(
	@Schema(description = "현재 비밀번호. 초기 비밀번호 상태면 발급받은 그 값이다", example = "Initial1234")
	@NotBlank(message = "현재 비밀번호는 필수 항목입니다")
	String currentPassword,

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
