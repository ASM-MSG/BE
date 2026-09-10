package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 링크 요청 (MSG-497 FR-22). 응답은 계정 존재 여부와 무관하게 항상 같은 성공이다 —
 * 이 API 가 가입 여부 조회기가 되지 않게 하는 계정 존재 은닉이다.
 */
@Schema(description = "비밀번호 재설정 링크 요청")
public record PasswordResetRequestDto(
	@Schema(description = "계정 이메일(아이디)", example = "organizer@fillmap.dev")
	@NotBlank(message = "이메일은 필수 항목입니다")
	@Email(message = "올바른 이메일 형식이 아닙니다")
	@Size(max = 255, message = "이메일은 최대 255자까지 가능합니다")
	String email
) {
}
