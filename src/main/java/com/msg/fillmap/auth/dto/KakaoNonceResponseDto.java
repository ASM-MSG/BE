package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
	description = "카카오 로그인 nonce 발급 응답. 같은 값이 HttpOnly 쿠키(OAUTH_NONCE)로도 내려간다.",
	requiredProperties = {"nonce"}
)
public record KakaoNonceResponseDto(
	@Schema(description = "카카오 인가 요청에 넣을 1회용 난수", example = "f47ac10b58cc4372a5670e02b2c3d479")
	String nonce
) {
}
