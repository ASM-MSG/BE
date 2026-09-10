package com.msg.fillmap.auth.oidc;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 애플 로그인 설정 (MSG-594). 앞 다섯은 공개 고정값이라 공통 application.yml 에 두고, 뒤 넷은 비밀이라
 * 환경변수(APPLE_TEAM_ID·APPLE_KEY_ID·APPLE_SIGNING_KEY·APPLE_TOKEN_ENCRYPTION_KEY)로만 들어온다.
 * 뒤 넷에 @NotBlank 를 안 다는 이유는 D-10 — 키가 없는 로컬 개발자도 부팅해 dev 모의 로그인(FR-12)을
 * 쓰게 두고, prod 는 ProdRequiredEnvValidator 가 미설정을 기동 실패로 잡는다.
 */
@Validated
@ConfigurationProperties(prefix = "oauth.apple")
public record AppleOidcProperties(
	@NotBlank String issuer,
	@NotBlank String jwkSetUri,
	@NotBlank String tokenUri,
	@NotBlank String revokeUri,
	// iOS 번들 ID — ID 토큰 aud 이자 토큰 API 의 client_id (D-6, dev·prod 단일값)
	@NotBlank String bundleId,
	// 클라이언트 비밀 JWT 의 iss
	String teamId,
	// 클라이언트 비밀 JWT 헤더의 kid
	String keyId,
	// .p8 의 BEGIN/END 줄을 뺀 Base64 본문 한 줄
	String signingKey,
	// 리프레시 토큰 암호화 키 — Base64 32바이트 (openssl rand -base64 32)
	String tokenEncryptionKey
) {
}
