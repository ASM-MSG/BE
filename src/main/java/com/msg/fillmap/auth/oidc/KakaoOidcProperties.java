package com.msg.fillmap.auth.oidc;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "oauth.kakao")
public record KakaoOidcProperties(
	@NotBlank String issuer,
	@NotBlank String jwkSetUri,
	@NotBlank String clientId,
	// 네이티브 앱 키 — 모바일 앱(네이티브 SDK) 경로 ID Token 의 aud (MSG-452). APK 에 포함되는 공개 식별자라 yml 에 둔다.
	@NotBlank String appClientId,
	// 인가 코드 → 토큰 교환 엔드포인트 (MSG-345). issuer·jwk-set-uri 와 같은 공개 고정값이라 공통 application.yml 에 둔다.
	@NotBlank String tokenUri,
	// 로그인 시작 시 브라우저를 보낼 카카오 인가 엔드포인트 (MSG-345). 서버가 여기에 쿼리를 붙여 302 한다.
	@NotBlank String authorizeUri,
	// nonce 쿠키를 Secure; SameSite=None 로 심을지 (MSG-345). 공통 true, http 로컬만 false —
	// Secure 쿠키는 http 에서 저장되지 않아 로컬 웹 로그인이 전부 2423 으로 죽는다.
	boolean nonceCookieSecure
) {
}