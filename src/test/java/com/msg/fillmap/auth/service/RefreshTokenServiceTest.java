package com.msg.fillmap.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.InMemoryInvalidatedTokenStore;
import com.msg.fillmap.auth.jwt.InMemoryRefreshTokenStore;
import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.jwt.JwtRefreshTokenProvider;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 로테이션·재사용 감지 시나리오 — 리프레시 JWT 는 실제 발급/파싱하고,
 * 저장소는 인메모리 페이크로 Redis 의존을 끊는다 (MSG-135 확정 결정 2).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService")
class RefreshTokenServiceTest {

	private static final Long USER_ID = 42L;
	private static final String DEVICE_ID = "device-1";

	@Mock
	private TokenProvider tokenProvider;

	@Mock
	private UserRepository userRepository;

	private InMemoryRefreshTokenStore refreshTokenStore;
	private InMemoryInvalidatedTokenStore invalidatedTokenStore;
	private RefreshTokenService refreshTokenService;

	@BeforeEach
	void setUp() {
		JwtProperties properties = new JwtProperties(
			"test-only-access-secret-must-be-at-least-32-bytes-long", Duration.ofHours(1),
			"test-only-refresh-secret-must-be-at-least-32-bytes-long", Duration.ofDays(14)
		);
		this.refreshTokenStore = new InMemoryRefreshTokenStore();
		this.invalidatedTokenStore = new InMemoryInvalidatedTokenStore();
		this.refreshTokenService = new RefreshTokenService(
			new JwtRefreshTokenProvider(properties), refreshTokenStore, tokenProvider, userRepository, properties,
			invalidatedTokenStore
		);
	}

	private void givenActiveUser() {
		User user = User.createLocalUser("test@example.com", "encoded-hash", "테스터");
		ReflectionTestUtils.setField(user, "id", USER_ID);
		given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
		given(tokenProvider.issueAccessToken(USER_ID, UserRole.USER)).willReturn("new-access-token");
	}

	private static ApiException apiException(Throwable thrown) {
		return (ApiException) thrown;
	}

	@Nested
	@DisplayName("reissue — 로테이션")
	class Rotation {

		// 검증: FR-AUTH-07
		@Test
		@DisplayName("성공: 새 액세스와 회전된 새 리프레시 + 토큰의 deviceId 를 반환한다")
		void 재발급하면_새_액세스와_회전된_새_리프레시를_반환한다() {
			givenActiveUser();
			String original = refreshTokenService.issue(USER_ID, DEVICE_ID);

			ReissueResult result = refreshTokenService.reissue(original);

			assertThat(result.accessToken()).isEqualTo("new-access-token");
			assertThat(result.refreshToken()).isNotBlank().isNotEqualTo(original);
			assertThat(result.deviceId()).isEqualTo(DEVICE_ID);
		}

		// 검증: FR-AUTH-07
		@Test
		@DisplayName("성공 후: 직전 리프레시는 더 이상 재발급에 쓸 수 없다")
		void 재발급_후_직전_리프레시는_무효화된다() {
			givenActiveUser();
			String original = refreshTokenService.issue(USER_ID, DEVICE_ID);
			refreshTokenService.reissue(original);

			assertThatThrownBy(() -> refreshTokenService.reissue(original))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(apiException(thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED));
		}
	}

	@Nested
	@DisplayName("reissue — 재사용 감지")
	class ReuseDetection {

		// 검증: FR-AUTH-07
		@Test
		@DisplayName("회전된 옛 토큰이 다시 들어오면 2433 을 던지고 (userId, deviceId) 체인을 삭제한다")
		void 회전된_옛_리프레시로_재발급하면_REUSE_DETECTED를_던지고_체인을_삭제한다() {
			givenActiveUser();
			String original = refreshTokenService.issue(USER_ID, DEVICE_ID);
			refreshTokenService.reissue(original);

			assertThatThrownBy(() -> refreshTokenService.reissue(original))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(apiException(thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED));

			assertThat(refreshTokenStore.findJti(USER_ID, DEVICE_ID)).isNull();
		}

		// 검증: FR-AUTH-07
		@Test
		@DisplayName("체인 폐기 후에는 최신 리프레시조차 EXPIRED 로 거절된다 — 재로그인 필요")
		void 재사용_감지로_체인이_폐기되면_이후_어떤_리프레시도_EXPIRED로_거절된다() {
			givenActiveUser();
			String original = refreshTokenService.issue(USER_ID, DEVICE_ID);
			ReissueResult rotated = refreshTokenService.reissue(original);
			assertThatThrownBy(() -> refreshTokenService.reissue(original))
				.isInstanceOf(ApiException.class);

			assertThatThrownBy(() -> refreshTokenService.reissue(rotated.refreshToken()))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(apiException(thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.EXPIRED_REFRESH_TOKEN));
		}
	}

	@Nested
	@DisplayName("reissue — 세션·유저 상태")
	class SessionState {

		@Test
		@DisplayName("저장소에 세션이 없으면(로그아웃/TTL 만료) EXPIRED_REFRESH_TOKEN")
		void Redis에_없는_리프레시는_EXPIRED_REFRESH_TOKEN을_던진다() {
			String refreshToken = refreshTokenService.issue(USER_ID, DEVICE_ID);
			refreshTokenStore.delete(USER_ID, DEVICE_ID);

			assertThatThrownBy(() -> refreshTokenService.reissue(refreshToken))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(apiException(thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.EXPIRED_REFRESH_TOKEN));
		}

		// 검증: FR-USER-09
		@Test
		@DisplayName("유저가 존재하지 않으면(탈퇴/삭제) INVALID_REFRESH_TOKEN")
		void 탈퇴한_유저의_리프레시는_재발급되지_않는다() {
			String refreshToken = refreshTokenService.issue(USER_ID, DEVICE_ID);
			given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> refreshTokenService.reissue(refreshToken))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(apiException(thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
		}

		// 검증: FR-AUTH-06
		@Test
		@DisplayName("디바이스 격리: 한쪽 재발급이 다른 디바이스 세션에 영향을 주지 않는다")
		void 같은_유저_다른_디바이스_세션은_한쪽_재발급이_다른쪽에_영향을_주지_않는다() {
			givenActiveUser();
			String deviceOneToken = refreshTokenService.issue(USER_ID, "device-1");
			String deviceTwoToken = refreshTokenService.issue(USER_ID, "device-2");

			refreshTokenService.reissue(deviceOneToken);
			ReissueResult deviceTwoResult = refreshTokenService.reissue(deviceTwoToken);

			assertThat(deviceTwoResult.deviceId()).isEqualTo("device-2");
			assertThat(refreshTokenStore.findJti(USER_ID, "device-1")).isNotNull();
			assertThat(refreshTokenStore.findJti(USER_ID, "device-2")).isNotNull();
		}
	}

	/**
	 * 비밀번호 재설정의 재발급 경합 차단 (MSG-497). 검사가 회전 save 뒤라 "세션 전량 삭제와 인터리빙돼
	 * 옛 리프레시로 세션이 부활하는" 경로가 닫힌다 — 여기서는 무효화 기록만 세워 그 판정을 직접 본다.
	 */
	@Nested
	@DisplayName("reissue — 사용자 단위 무효화 차단")
	class ResetInvalidation {

		private void 무효화_기록을_세운다(Instant invalidatedAt) {
			invalidatedTokenStore.invalidateUser(USER_ID, invalidatedAt, Duration.ofDays(14));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("무효화 이전에 발급된 리프레시의 재발급은 거부되고 회전 세션도 남지 않는다")
		void 재설정_이전_발급_리프레시의_재발급은_세션을_남기지_않는다() {
			givenActiveUser();
			String refreshToken = refreshTokenService.issue(USER_ID, DEVICE_ID);
			무효화_기록을_세운다(Instant.now().plusSeconds(10));

			assertThatThrownBy(() -> refreshTokenService.reissue(refreshToken))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(apiException(thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.EXPIRED_REFRESH_TOKEN));
			assertThat(refreshTokenStore.findJti(USER_ID, DEVICE_ID)).isNull();
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("무효화와 같은 초에 발급된 리프레시도 거부된다 — fail-closed 경계")
		void 무효화와_같은_초에_발급된_리프레시도_거부된다() {
			givenActiveUser();
			String refreshToken = refreshTokenService.issue(USER_ID, DEVICE_ID);
			무효화_기록을_세운다(Instant.now());

			assertThatThrownBy(() -> refreshTokenService.reissue(refreshToken))
				.isInstanceOf(ApiException.class);
			assertThat(refreshTokenStore.findJti(USER_ID, DEVICE_ID)).isNull();
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("무효화 이후 재로그인한 리프레시의 재발급은 통과한다")
		void 재설정_후_재로그인한_리프레시의_재발급은_통과한다() {
			givenActiveUser();
			무효화_기록을_세운다(Instant.now().minusSeconds(10));
			String refreshToken = refreshTokenService.issue(USER_ID, DEVICE_ID);

			ReissueResult result = refreshTokenService.reissue(refreshToken);

			assertThat(result.accessToken()).isEqualTo("new-access-token");
			assertThat(refreshTokenStore.findJti(USER_ID, DEVICE_ID)).isNotNull();
		}
	}
}
