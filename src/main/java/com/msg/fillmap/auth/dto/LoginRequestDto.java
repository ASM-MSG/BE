package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "이메일/비밀번호 로그인 요청")
public record LoginRequestDto(
	@Schema(description = "가입한 이메일", example = "user@fillmap.dev")
	@NotBlank(message = "이메일은 필수 항목입니다")
	@Email(message = "올바른 이메일 형식이 아닙니다")
	String email,

	@Schema(description = "비밀번호 (영문+숫자 포함 8~64자)", example = "Fillmap1234")
	@NotBlank(message = "비밀번호는 필수 항목입니다")
	String password
) {
}
