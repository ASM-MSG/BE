package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 성공 응답")
public record LoginResponseDto(
	@Schema(description = "발급된 JWT 액세스 토큰. 이후 요청 Authorization 헤더에 'Bearer {토큰}'으로 넣는다.",
		example = "eyJhbGciOiJIUzI1NiJ9...")
	String accessToken
) {
}
