package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

@Schema(description = "소셜(OIDC) 로그인 요청")
public record OidcLoginRequestDto(
	@Schema(description = "소셜 제공자(카카오 등)에서 발급받은 OIDC ID Token", example = "eyJraWQiOiI...")
	@NotBlank(message = "idToken은 필수 항목입니다")
	String idToken
) {
}
