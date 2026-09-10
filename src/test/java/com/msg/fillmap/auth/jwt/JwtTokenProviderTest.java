package com.msg.fillmap.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
	private InMemoryInvalidatedTokenStore invalidatedTokenStore;

	private static JwtProperties properties(String secret, Duration accessTokenTtl) {
		return new JwtProperties(secret, accessTokenTtl, REFRESH_SECRET, Duration.ofDays(14));
	}

	@BeforeEach
	void setUp() {
		JwtProperties props = properties(SECRET, Duration.ofHours(1));
		this.invalidatedTokenStore = new InMemoryInvalidatedTokenStore();
		this.tokenProvider = new JwtTokenProvider(props, invalidatedTokenStore);
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

	/**
	 * 사용자 단위 무효화 (MSG-497) — 비밀번호 재설정이 그 계정의 <b>기존 액세스 토큰</b>까지 끊는다.
	 * 판정은 토큰의 발급 시각(iat)과 무효화 시각의 비교이고, 검사 대상은 ORG·ADMIN 역할 토큰뿐이다.
	 */
	@Nested
	@DisplayName("사용자 단위 무효화 (MSG-497)")
	class UserInvalidation {

		private static final long USER_ID = 7L;

		/** 토큰의 iat 를 그대로 읽는다 — "같은 초" 경계를 벽시계 추정 없이 정확히 겨눈다. */
		private long issuedAtEpochSecond(String token) {
			String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
				StandardCharsets.UTF_8);
			Matcher matcher = Pattern.compile("\"iat\":(\\d+)").matcher(payload);
			assertThat(matcher.find()).isTrue();
			return Long.parseLong(matcher.group(1));
		}

		private void 무효화_기록을_세운다(long epochSecond) {
			invalidatedTokenStore.invalidateUser(USER_ID, Instant.ofEpochSecond(epochSecond), Duration.ofDays(14));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("무효화 이전에 발급된 ORG 토큰은 거부된다")
		void 재설정_성공_시_기존_액세스_토큰이_즉시_거부된다() {
			String token = tokenProvider.issueAccessToken(USER_ID, UserRole.ORG);
			무효화_기록을_세운다(issuedAtEpochSecond(token) + 1);

			assertThatThrownBy(() -> tokenProvider.parseAccessToken(token))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(((ApiException) thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.INVALID_TOKEN));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("무효화와 같은 초에 발급된 기존 토큰도 거부된다 — fail-closed 경계")
		void 무효화와_같은_초에_발급된_기존_토큰도_거부된다() {
			String token = tokenProvider.issueAccessToken(USER_ID, UserRole.ORG);
			무효화_기록을_세운다(issuedAtEpochSecond(token));

			assertThatThrownBy(() -> tokenProvider.parseAccessToken(token))
				.isInstanceOf(ApiException.class);
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("무효화 다음 초 이후 로그인한 토큰은 통과한다")
		void 무효화_다음_초_이후_로그인한_토큰은_통과한다() {
			String token = tokenProvider.issueAccessToken(USER_ID, UserRole.ORG);
			무효화_기록을_세운다(issuedAtEpochSecond(token) - 1);

			assertThat(tokenProvider.parseAccessToken(token).userId()).isEqualTo(USER_ID);
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("ADMIN 토큰도 같은 검사를 받는다 — 재설정 대상이 ORG·ADMIN 이라서다")
		void ADMIN_토큰도_무효화_검사를_받는다() {
			String token = tokenProvider.issueAccessToken(USER_ID, UserRole.ADMIN);
			무효화_기록을_세운다(issuedAtEpochSecond(token));

			assertThatThrownBy(() -> tokenProvider.parseAccessToken(token))
				.isInstanceOf(ApiException.class);
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("USER 토큰은 검사 스코프 밖이라 마커가 있어도 통과한다 — 요청당 Redis 왕복 절약")
		void USER_토큰은_사용자_단위_무효화_검사를_받지_않는다() {
			String token = tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
			무효화_기록을_세운다(issuedAtEpochSecond(token) + 1);

			assertThat(tokenProvider.parseAccessToken(token).role()).isEqualTo(UserRole.USER);
		}
	}
}
