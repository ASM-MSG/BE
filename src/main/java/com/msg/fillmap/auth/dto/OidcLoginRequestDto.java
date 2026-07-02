package com.msg.fillmap.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OidcLoginRequestDto(
	@NotBlank(message = "idToken은 필수 항목입니다")
	String idToken
) {
}