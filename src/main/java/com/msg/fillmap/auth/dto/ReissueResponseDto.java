package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 재발급 성공 응답", requiredProperties = {"accessToken"})
public record ReissueResponseDto(
	@Schema(description = "새로 발급된 JWT 액세스 토큰.", example = "eyJhbGciOiJIUzI1NiJ9...")
	String accessToken,
	@Schema(description = "회전된 새 리프레시 토큰. 앱(X-Client-Type: app)만 값이 채워지고, "
		+ "웹은 HttpOnly 쿠키(Set-Cookie)로 재설정되므로 null 이다.",
		example = "eyJhbGciOiJIUzI1NiJ9...")
	String refreshToken
) {
}
