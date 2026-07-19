package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 성공 응답")
public record LoginResponseDto(
	@Schema(description = "발급된 JWT 액세스 토큰. 이후 요청 Authorization 헤더에 'Bearer {토큰}'으로 넣는다.",
		example = "eyJhbGciOiJIUzI1NiJ9...")
	String accessToken,
	@Schema(description = "발급된 리프레시 토큰. 앱(X-Client-Type: app)만 값이 채워지고, "
		+ "웹은 HttpOnly 쿠키(Set-Cookie)로 내려가므로 null 이다.",
		example = "eyJhbGciOiJIUzI1NiJ9...")
	String refreshToken
) {
}
