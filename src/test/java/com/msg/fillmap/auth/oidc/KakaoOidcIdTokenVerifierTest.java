package com.msg.fillmap.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("KakaoOidcIdTokenVerifier")
class KakaoOidcIdTokenVerifierTest {

	@Mock
	private JwtDecoder kakaoJwtDecoder;

	private KakaoOidcIdTokenVerifier verifier;

	@BeforeEach
	void setUp() {
		verifier = new KakaoOidcIdTokenVerifier(kakaoJwtDecoder);
	}

	@Test
	@DisplayName("supports: KAKAO 를 반환한다")
	void supports_kakao() {
		assertThat(verifier.supports()).isEqualTo(AuthProvider.KAKAO);
	}

	@Test
	@DisplayName("성공: 검증된 ID Token 에서 subject/email/nickname 을 추출한다")
	void verify_success() {
		Jwt jwt = Jwt.withTokenValue("id-token")
			.header("alg", "RS256")
			.claim("sub", "kakao-12345")
			.claim("email", "test@kakao.com")
			.claim("nickname", "카카오유저")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(60))
			.build();
		given(kakaoJwtDecoder.decode("id-token")).willReturn(jwt);

		OidcUserInfo info = verifier.verify("id-token");

		assertThat(info.oid()).isEqualTo("kakao-12345");
		assertThat(info.email()).isEqualTo("test@kakao.com");
		assertThat(info.nickname()).isEqualTo("카카오유저");
	}

	@Test
	@DisplayName("실패: 서명·발급자·audience 검증에 실패하면 INVALID_ID_TOKEN ApiException 을 던진다")
	void verify_invalidToken() {
		given(kakaoJwtDecoder.decode("bad-token")).willThrow(new BadJwtException("invalid"));

		assertThatThrownBy(() -> verifier.verify("bad-token"))
			.isInstanceOf(ApiException.class)
			.satisfies(thrown -> {
				ApiException api = (ApiException)thrown;
				assertThat(api.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_ID_TOKEN);
			});
	}
}
