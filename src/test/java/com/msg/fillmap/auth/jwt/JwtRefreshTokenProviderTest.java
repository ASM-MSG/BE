package com.msg.fillmap.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;

@DisplayName("JwtRefreshTokenProvider")
class JwtRefreshTokenProviderTest {

	private static final String ACCESS_SECRET = "test-only-access-secret-must-be-at-least-32-bytes-long";
	private static final String REFRESH_SECRET = "test-only-refresh-secret-must-be-at-least-32-bytes-long";
	private static final String OTHER_SECRET = "another-completely-different-secret-32-bytes-plus-long";

	private JwtRefreshTokenProvider refreshTokenProvider;

	private static JwtProperties properties(String refreshSecret, Duration refreshTokenTtl) {
		return new JwtProperties(ACCESS_SECRET, Duration.ofHours(1), refreshSecret, refreshTokenTtl);
	}

	@BeforeEach
	void setUp() {
		this.refreshTokenProvider = new JwtRefreshTokenProvider(properties(REFRESH_SECRET, Duration.ofDays(14)));
	}

	@Nested
	@DisplayName("issueRefreshToken → parseRefreshToken 라운드트립")
	class RoundTrip {

		@Test
		@DisplayName("성공: 발급한 토큰을 파싱하면 원본 userId·deviceId·jti 를 그대로 얻는다")
		void 리프레시_토큰은_userId와_deviceId와_jti를_담아_왕복파싱된다() {
			String token = refreshTokenProvider.issueRefreshToken(42L, "device-1", "jti-abc");

			RefreshTokenClaims claims = refreshTokenProvider.parseRefreshToken(token);

			assertThat(claims.userId()).isEqualTo(42L);
			assertThat(claims.deviceId()).isEqualTo("device-1");
			assertThat(claims.jti()).isEqualTo("jti-abc");
		}
	}

	@Nested
	@DisplayName("parseRefreshToken 검증 실패")
	class ParseFailure {

		@Test
		@DisplayName("typ 이 refresh 가 아니면 INVALID_REFRESH_TOKEN — 액세스 토큰 오용 차단")
		void typ이_refresh가_아닌_토큰은_INVALID_REFRESH_TOKEN을_던진다() {
			Instant now = Instant.now();
			String notRefreshTyped = Jwts.builder()
				.subject("42")
				.claim("did", "device-1")
				.claim("typ", "access")
				.id("jti-abc")
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(Duration.ofDays(14))))
				.signWith(Keys.hmacShaKeyFor(REFRESH_SECRET.getBytes(StandardCharsets.UTF_8)))
				.compact();

			assertThatThrownBy(() -> refreshTokenProvider.parseRefreshToken(notRefreshTyped))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(((ApiException) thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
		}

		@Test
		@DisplayName("exp 가 지난 토큰이면 EXPIRED_REFRESH_TOKEN")
		void 만료된_리프레시_토큰은_EXPIRED_REFRESH_TOKEN을_던진다() {
			JwtRefreshTokenProvider expiredIssuer =
				new JwtRefreshTokenProvider(properties(REFRESH_SECRET, Duration.ofSeconds(-1)));
			String expiredToken = expiredIssuer.issueRefreshToken(42L, "device-1", "jti-abc");

			assertThatThrownBy(() -> refreshTokenProvider.parseRefreshToken(expiredToken))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(((ApiException) thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.EXPIRED_REFRESH_TOKEN));
		}

		@Test
		@DisplayName("다른 시크릿으로 서명된 토큰이면 INVALID_REFRESH_TOKEN")
		void 서명이_위조된_리프레시_토큰은_INVALID_REFRESH_TOKEN을_던진다() {
			JwtRefreshTokenProvider forger =
				new JwtRefreshTokenProvider(properties(OTHER_SECRET, Duration.ofDays(14)));
			String forgedToken = forger.issueRefreshToken(42L, "device-1", "jti-abc");

			assertThatThrownBy(() -> refreshTokenProvider.parseRefreshToken(forgedToken))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(((ApiException) thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
		}
	}
}
