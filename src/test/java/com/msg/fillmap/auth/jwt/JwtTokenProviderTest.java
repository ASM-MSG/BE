package com.msg.fillmap.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.UserRole;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

	private static final String SECRET = "test-only-secret-must-be-at-least-32-bytes-long-for-hs256";
	private static final String OTHER_SECRET = "another-completely-different-secret-32-bytes-plus-long";
	private static final String REFRESH_SECRET = "test-only-refresh-secret-must-be-at-least-32-bytes-long";

	private JwtTokenProvider tokenProvider;

	private static JwtProperties properties(String secret, Duration accessTokenTtl) {
		return new JwtProperties(secret, accessTokenTtl, REFRESH_SECRET, Duration.ofDays(14));
	}

	@BeforeEach
	void setUp() {
		JwtProperties props = properties(SECRET, Duration.ofHours(1));
		this.tokenProvider = new JwtTokenProvider(props, new InMemoryInvalidatedTokenStore());
	}

	@Nested
	@DisplayName("issueAccessToken → parseAccessToken 라운드트립")
	class RoundTrip {

		@Test
		@DisplayName("성공: 발급한 토큰을 파싱하면 원본 userId 와 role 을 그대로 얻는다")
		void roundTrip_success() {
			String token = tokenProvider.issueAccessToken(42L, UserRole.USER);

			AuthPrincipal principal = tokenProvider.parseAccessToken(token);

			assertThat(principal.userId()).isEqualTo(42L);
			assertThat(principal.role()).isEqualTo(UserRole.USER);
		}

		@Test
		@DisplayName("성공: ADMIN role 도 정확히 라운드트립된다")
		void roundTrip_admin() {
			String token = tokenProvider.issueAccessToken(1L, UserRole.ADMIN);

			AuthPrincipal principal = tokenProvider.parseAccessToken(token);

			assertThat(principal.role()).isEqualTo(UserRole.ADMIN);
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("성공: ORG role 도 정확히 라운드트립된다 — 행사 운영자 토큰 (MSG-496)")
		void ORG_역할로_발급한_토큰을_파싱하면_ORG_주체가_된다() {
			String token = tokenProvider.issueAccessToken(7L, UserRole.ORG);

			AuthPrincipal principal = tokenProvider.parseAccessToken(token);

			assertThat(principal.userId()).isEqualTo(7L);
			assertThat(principal.role()).isEqualTo(UserRole.ORG);
		}
	}

	@Nested
	@DisplayName("invalidateAccessToken")
	class Invalidate {

		// 검증: FR-AUTH-09
		@Test
		@DisplayName("성공: 무효화한 토큰은 이후 INVALID_TOKEN 으로 거부된다")
		void invalidate_success() {
			String token = tokenProvider.issueAccessToken(42L, UserRole.USER);

			tokenProvider.invalidateAccessToken(token);

			assertThatThrownBy(() -> tokenProvider.parseAccessToken(token))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException api = (ApiException) thrown;
					assertThat(api.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_TOKEN);
				});
		}
	}

	@Nested
	@DisplayName("parseAccessToken 검증 실패")
	class ParseFailure {

		@Test
		@DisplayName("만료된 토큰이면 EXPIRED_TOKEN")
		void expired() {
			JwtProperties expiredProps = properties(SECRET, Duration.ofSeconds(-1));
			JwtTokenProvider expiredIssuer = new JwtTokenProvider(expiredProps, new InMemoryInvalidatedTokenStore());
			String expiredToken = expiredIssuer.issueAccessToken(1L, UserRole.USER);

			assertThatThrownBy(() -> tokenProvider.parseAccessToken(expiredToken))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException api = (ApiException) thrown;
					assertThat(api.getErrorCode()).isEqualTo(AuthErrorCode.EXPIRED_TOKEN);
				});
		}

		@Test
		@DisplayName("다른 시크릿으로 발급된 토큰이면 INVALID_TOKEN")
		void forgedSignature() {
			JwtProperties otherProps = properties(OTHER_SECRET, Duration.ofHours(1));
			JwtTokenProvider forger = new JwtTokenProvider(otherProps, new InMemoryInvalidatedTokenStore());
			String forgedToken = forger.issueAccessToken(1L, UserRole.USER);

			assertThatThrownBy(() -> tokenProvider.parseAccessToken(forgedToken))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException api = (ApiException) thrown;
					assertThat(api.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_TOKEN);
				});
		}

		@Test
		@DisplayName("JWT 가 아닌 문자열이면 INVALID_TOKEN")
		void malformed() {
			assertThatThrownBy(() -> tokenProvider.parseAccessToken("not-a-jwt-at-all"))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException api = (ApiException) thrown;
					assertThat(api.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_TOKEN);
				});
		}

		@Test
		@DisplayName("null 토큰이면 INVALID_TOKEN")
		void nullToken() {
			assertThatThrownBy(() -> tokenProvider.parseAccessToken(null))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException api = (ApiException) thrown;
					assertThat(api.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_TOKEN);
				});
		}

		@Test
		@DisplayName("빈 문자열 토큰이면 INVALID_TOKEN")
		void blankToken() {
			assertThatThrownBy(() -> tokenProvider.parseAccessToken(""))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException api = (ApiException) thrown;
					assertThat(api.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_TOKEN);
				});
		}
	}
}
