package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 성공 응답", requiredProperties = {"accessToken", "refreshToken", "role"})
public record LoginResponseDto(
	@Schema(description = "발급된 JWT 액세스 토큰. 이후 요청 Authorization 헤더에 'Bearer {토큰}'으로 넣는다.",
		example = "eyJhbGciOiJIUzI1NiJ9...")
	String accessToken,
	@Schema(description = "발급된 리프레시 토큰. 앱(X-Client-Type: app)만 값이 채워지고, "
		+ "웹은 HttpOnly 쿠키(Set-Cookie)로 내려가므로 null 이다.",
		example = "eyJhbGciOiJIUzI1NiJ9...", nullable = true)
	String refreshToken,
	@Schema(description = "로그인한 사용자의 역할. 화면이 일반 사용자·행사 운영자·관리자 진입을 가르는 재료다 (MSG-496).",
		example = "USER", allowableValues = {"USER", "ORG", "ADMIN"})
	String role
) {
}
