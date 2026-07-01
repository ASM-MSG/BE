package com.msg.fillmap.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequestDto(
	@NotBlank(message = "이메일은 필수 항목입니다")
	@Email(message = "올바른 이메일 형식이 아닙니다")
	@Size(max = 255, message = "이메일은 최대 255자까지 가능합니다")
	String email,

	@NotBlank(message = "비밀번호는 필수 항목입니다")
	@Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하이어야 합니다")
	@Pattern(
		regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
		message = "비밀번호는 영문과 숫자를 각각 하나 이상 포함해야 합니다"
	)
	String password,

	@NotBlank(message = "닉네임은 필수 항목입니다")
	@Size(min = 2, max = 20, message = "닉네임은 2자 이상 20자 이하이어야 합니다")
	String nickname
) {
}
