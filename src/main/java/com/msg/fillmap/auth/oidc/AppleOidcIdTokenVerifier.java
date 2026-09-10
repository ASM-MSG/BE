package com.msg.fillmap.auth.oidc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;

/**
 * 애플 ID 토큰 검증기 (MSG-594 D-4). 서명·발급자·audience(번들 ID)·만료는 appleJwtDecoder 가 보고, 여기서는
 * 요청 본문의 nonce 원문을 SHA-256 해서 토큰의 nonce 클레임과 상수 시간 비교한다 — 앱이 애플 시트에 해시를
 * 넘기고 원문을 서버에 보내는 계약이라(스펙 "앱이 지켜야 할 흐름" 2) 원문을 넘기면 대조가 항상 실패한다.
 * 실패 원인은 전부 2421 하나로 수렴한다(어느 검사가 실패했는지는 공격자에게도 같은 정보라 warn 로그로만 가른다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppleOidcIdTokenVerifier implements OidcIdTokenVerifier {

	private static final String NONCE_CLAIM = "nonce";

	@Qualifier("appleJwtDecoder")
	private final JwtDecoder appleJwtDecoder;

	@Override
	public AuthProvider supports() {
		return AuthProvider.APPLE;
	}

	@Override
	public OidcUserInfo verify(String idToken, String nonce) {
		if (nonce == null || nonce.isBlank()) {
			// 대조할 기대값이 없으면 방어가 없는 것과 같다 — JWKS 조회(디코더) 전에 끊는다
			log.warn("애플 로그인 요청에 nonce 없음 — 2421 으로 수렴");
			throw new ApiException(AuthErrorCode.INVALID_ID_TOKEN);
		}
		Jwt jwt = decode(idToken, AuthErrorCode.INVALID_ID_TOKEN);
		String claim = jwt.getClaimAsString(NONCE_CLAIM);
		if (claim == null || !MessageDigest.isEqual(
			sha256Hex(nonce).getBytes(StandardCharsets.UTF_8), claim.getBytes(StandardCharsets.UTF_8))) {
			// 불일치 사실만 남긴다 — nonce·토큰 원문은 로그 금지 항목
			log.warn("애플 ID 토큰의 nonce 클레임 불일치(부재={}) — 2421 으로 수렴", claim == null);
			throw new ApiException(AuthErrorCode.INVALID_ID_TOKEN);
		}
		// 애플 ID 토큰에는 이름이 없다 — 닉네임은 요청 body 의 fullName 으로 따로 온다(FR-6)
		return new OidcUserInfo(jwt.getSubject(), jwt.getClaimAsString("email"), null);
	}

	/**
	 * 인가 코드 교환 응답의 id_token 전용 (AC-594-15). nonce 를 보지 않는 이유: 서버가 애플 토큰 엔드포인트에서
	 * TLS 로 직접 받은 응답이라 "다른 시도의 토큰을 클라이언트가 다시 보내는" 경로가 없다. 필요한 결속은 요청 ID
	 * 토큰과 같은 sub 인지 하나이고 그 비교는 OidcLoginService 가 한다. 실패가 2423 인 이유는 잘못된 것이
	 * 요청의 ID 토큰이 아니라 인가 코드 쪽이기 때문이다.
	 */
	public String verifySubject(String idToken) {
		return decode(idToken, AuthErrorCode.INVALID_AUTHORIZATION_CODE).getSubject();
	}

	private Jwt decode(String idToken, AuthErrorCode onFailure) {
		try {
			return appleJwtDecoder.decode(idToken);
		} catch (JwtException e) {
			throw new ApiException(onFailure, e);
		}
	}

	private static String sha256Hex(String nonce) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 은 모든 JVM 에 있다", e);
		}
	}
}
