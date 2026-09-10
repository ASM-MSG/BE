package com.msg.fillmap.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

/**
 * 애플 ID 토큰 검증기 (MSG-594 D-4). 서명·발급자·audience·만료는 디코더(mock) 몫이고 여기서는 nonce
 * 대조와 클레임 추출만 본다. nonce 기대값은 알려진 SHA-256 벡터("abc")를 리터럴로 박아 구현과 독립이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppleOidcIdTokenVerifier")
class AppleOidcIdTokenVerifierTest {

	private static final String NONCE = "abc";
	// SHA-256("abc") — FIPS 180-2 표준 벡터
	private static final String NONCE_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

	@Mock
	private JwtDecoder appleJwtDecoder;

	private AppleOidcIdTokenVerifier verifier;

	@BeforeEach
	void setUp() {
		verifier = new AppleOidcIdTokenVerifier(appleJwtDecoder);
	}

	private static Jwt.Builder appleJwt() {
		return Jwt.withTokenValue("id-token")
			.header("alg", "RS256")
			.claim("sub", "001234.abcd1234")
			.claim("email", "user@privaterelay.appleid.com")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(60));
	}

	private static void assertErrorCode(Throwable thrown, AuthErrorCode expected) {
		assertThat(thrown).isInstanceOf(ApiException.class);
		assertThat(((ApiException)thrown).getErrorCode()).isEqualTo(expected);
	}

	@Test
	void supports는_APPLE을_반환한다() {
		assertThat(verifier.supports()).isEqualTo(AuthProvider.APPLE);
	}

	// 검증: FR-AUTH-12, AC-594-01
	@Test
	@DisplayName("검증된 ID 토큰에서 sub·email 을 추출하고 닉네임은 null 이다 — 애플은 이름을 토큰에 넣지 않는다")
	void 검증된_ID_토큰에서_sub와_email을_추출하고_닉네임은_null이다() {
		given(appleJwtDecoder.decode("id-token")).willReturn(appleJwt().claim("nonce", NONCE_HASH).build());

		OidcUserInfo info = verifier.verify("id-token", NONCE);

		assertThat(info.oid()).isEqualTo("001234.abcd1234");
		assertThat(info.email()).isEqualTo("user@privaterelay.appleid.com");
		assertThat(info.nickname()).isNull();
	}

	// 검증: FR-AUTH-12, AC-594-03
	@Test
	void nonce_원문의_SHA256_해시가_클레임과_같으면_통과한다() {
		given(appleJwtDecoder.decode("id-token")).willReturn(appleJwt().claim("nonce", NONCE_HASH).build());

		assertThat(verifier.verify("id-token", NONCE).oid()).isEqualTo("001234.abcd1234");
	}

	// 검증: FR-AUTH-12, AC-594-03
	@Test
	void nonce_해시가_클레임과_다르면_2421이다() {
		given(appleJwtDecoder.decode("id-token")).willReturn(appleJwt().claim("nonce", NONCE_HASH).build());

		assertThatThrownBy(() -> verifier.verify("id-token", "other-nonce"))
			.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_ID_TOKEN));
	}

	// 검증: FR-AUTH-12, AC-594-03
	@Test
	void nonce_클레임이_없으면_2421이다() {
		given(appleJwtDecoder.decode("id-token")).willReturn(appleJwt().build());

		assertThatThrownBy(() -> verifier.verify("id-token", NONCE))
			.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_ID_TOKEN));
	}

	// 검증: FR-AUTH-12, AC-594-03
	@Test
	@DisplayName("요청 nonce 가 없으면 디코더(JWKS 조회)를 부르지 않고 2421 이다")
	void 요청_nonce가_없으면_디코더를_부르지_않고_2421이다() {
		assertThatThrownBy(() -> verifier.verify("id-token", null))
			.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_ID_TOKEN));
		assertThatThrownBy(() -> verifier.verify("id-token", " "))
			.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_ID_TOKEN));

		verify(appleJwtDecoder, never()).decode(any());
	}

	// 검증: FR-AUTH-12, AC-594-02
	@Test
	void 서명_발급자_audience_만료_검증에_실패하면_2421이다() {
		given(appleJwtDecoder.decode("bad-token")).willThrow(new BadJwtException("invalid"));

		assertThatThrownBy(() -> verifier.verify("bad-token", NONCE))
			.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_ID_TOKEN))
			.hasCauseInstanceOf(BadJwtException.class);
	}

	// 검증: FR-AUTH-12, AC-594-15
	@Test
	@DisplayName("verifySubject 는 검증된 토큰의 sub 를 돌려주고 nonce 는 보지 않는다 — 서버가 애플에서 직접 받은 토큰")
	void verifySubject는_검증된_토큰의_sub를_돌려주고_nonce는_보지_않는다() {
		given(appleJwtDecoder.decode("exchanged-id-token")).willReturn(appleJwt().build());

		assertThat(verifier.verifySubject("exchanged-id-token")).isEqualTo("001234.abcd1234");
	}

	// 검증: FR-AUTH-12, AC-594-15
	@Test
	@DisplayName("verifySubject 의 검증 실패는 2423 — 잘못된 것은 요청 ID 토큰이 아니라 인가 코드 쪽이다")
	void verifySubject는_검증_실패_시_2423이다() {
		given(appleJwtDecoder.decode("bad-token")).willThrow(new BadJwtException("invalid"));

		assertThatThrownBy(() -> verifier.verifySubject("bad-token"))
			.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_AUTHORIZATION_CODE))
			.hasCauseInstanceOf(BadJwtException.class);
	}
}
