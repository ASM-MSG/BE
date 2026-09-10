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

	@Override
	public OidcUserInfo verify(String idToken) {
		try {
			Jwt jwt = kakaoJwtDecoder.decode(idToken);
			return new OidcUserInfo(jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("nickname"));
		} catch (JwtException e) {
			throw new ApiException(AuthErrorCode.INVALID_ID_TOKEN, e);
		}
	}
}