package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 비밀번호 재설정 확정 (MSG-497 FR-22). 메일 링크의 토큰은 1회만 쓰이고 유효 시간은 30분이다. */
@Schema(description = "비밀번호 재설정 확정 요청")
public record PasswordResetConfirmRequestDto(
	@Schema(description = "재설정 링크의 token 쿼리 값", example = "9pQ2f7Zk...")
	@NotBlank(message = "재설정 토큰은 필수 항목입니다")
	String token,

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
