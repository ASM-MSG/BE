package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 재발급 요청. 웹은 리프레시 토큰이 쿠키(refreshToken)로 전송되므로 body 를 생략할 수 있다.")
public record ReissueRequestDto(
	@Schema(description = "앱(X-Client-Type: app) 클라이언트의 리프레시 토큰. 웹은 쿠키를 사용하므로 생략.",
		example = "eyJhbGciOiJIUzI1NiJ9...")
	String refreshToken
) {
}
