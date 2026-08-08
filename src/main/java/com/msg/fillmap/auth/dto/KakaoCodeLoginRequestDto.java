package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

@Schema(description = "카카오 인가 코드 로그인 요청 (웹). 카카오 콜백으로 받은 코드를 서버가 ID Token 으로 교환한다.")
public record KakaoCodeLoginRequestDto(
	@Schema(description = "카카오 콜백 쿼리로 받은 1회용 인가 코드", example = "vBv8oXbeLnDF2mkw...")
	@NotBlank(message = "code는 필수 항목입니다")
	String code,

	@Schema(
		description = "인가 요청에 사용한 redirect URI 그대로. 카카오 콘솔 등록값과 정확히 일치해야 한다.",
		example = "http://localhost:5173/oauth/kakao/callback"
	)
	@NotBlank(message = "redirectUri는 필수 항목입니다")
	String redirectUri
) {
}
