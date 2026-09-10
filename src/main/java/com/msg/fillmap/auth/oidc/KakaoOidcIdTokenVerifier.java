package com.msg.fillmap.auth.oidc;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;

@Component
@RequiredArgsConstructor
public class KakaoOidcIdTokenVerifier implements OidcIdTokenVerifier {

	@Qualifier("kakaoJwtDecoder")
	private final JwtDecoder kakaoJwtDecoder;

	@Override
	public AuthProvider supports() {
		return AuthProvider.KAKAO;
	}

	/**
	 * nonce 는 받기만 하고 쓰지 않는다 — 모바일 카카오 경로는 nonce 를 도입하지 않았고(MSG-594 PRD 미해결 4,
	 * 별도 판단), 웹 카카오 경로의 nonce 는 KakaoAuthCodeExchanger 가 교환 직후 쿠키값으로 이미 대조한다(MSG-345).
	 */
	@Override
	public OidcUserInfo verify(String idToken, String nonce) {
		try {
			Jwt jwt = kakaoJwtDecoder.decode(idToken);
			return new OidcUserInfo(jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("nickname"));
		} catch (JwtException e) {
			throw new ApiException(AuthErrorCode.INVALID_ID_TOKEN, e);
		}
	}
}