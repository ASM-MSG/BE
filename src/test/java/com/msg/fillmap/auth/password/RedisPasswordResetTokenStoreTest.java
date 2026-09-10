package com.msg.fillmap.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 실제 Redis(localhost:6379)를 쓰는 재설정 토큰 저장소 테스트 (MSG-497). Lua 원자화와 두 키(정방향·역방향)
 * 정합이 검증 대상이라 페이크로는 대체되지 않는다 — RedisInvalidatedTokenStoreTest 와 같은 구성이다.
 * 키는 매번 새 UUID 토큰·새 userId 라 공유 로컬 Redis 에 다른 테스트와 겹치지 않고, TTL 로 자연 소멸한다.
 */
@DisplayName("RedisPasswordResetTokenStore")
class RedisPasswordResetTokenStoreTest {

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static RedisPasswordResetTokenStore store;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		store = new RedisPasswordResetTokenStore(redisTemplate);
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	private static String newToken() {
		return "reset-token-" + UUID.randomUUID();
	}

	private static long newUserId() {
		return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
	}

	private static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String forwardKey(String token) {
		return RedisPasswordResetTokenStore.FORWARD_PREFIX + sha256Hex(token);
	}

	private static String reverseKey(long userId) {
		return RedisPasswordResetTokenStore.REVERSE_PREFIX + userId;
	}

	@Nested
	@DisplayName("발급·저장")
	class Issue {

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("저장소에는 해시만 남는다 — 토큰 원문 키가 없다")
		void 토큰_저장소에는_해시만_저장된다() {
			String token = newToken();
			long userId = newUserId();

			store.save(token, userId);

			assertThat(redisTemplate.hasKey(RedisPasswordResetTokenStore.FORWARD_PREFIX + token)).isFalse();
			assertThat(redisTemplate.opsForValue().get(forwardKey(token))).isEqualTo(String.valueOf(userId));
			assertThat(redisTemplate.opsForValue().get(reverseKey(userId))).isEqualTo(sha256Hex(token));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("두 키 모두 TTL 30분이다 — 만료 판정은 저장소가 전담한다")
		void 토큰_TTL이_30분으로_설정된다() {
			String token = newToken();
			long userId = newUserId();

			store.save(token, userId);

			assertThat(redisTemplate.getExpire(forwardKey(token))).isBetween(1_700L, 1_800L);
			assertThat(redisTemplate.getExpire(reverseKey(userId))).isBetween(1_700L, 1_800L);
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("재요청하면 이전 링크가 즉시 무효가 된다 — 사용자당 활성 1개")
		void 재요청하면_이전_링크가_즉시_무효가_된다() {
			String first = newToken();
			String second = newToken();
			long userId = newUserId();
			store.save(first, userId);

			store.save(second, userId);

			assertThat(store.consume(first)).isNull();
			assertThat(store.consume(second)).isEqualTo(userId);
		}
	}

	@Nested
	@DisplayName("소비·폐기·복원")
	class ConsumeAndRevoke {

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("같은 토큰의 두 번째 사용은 거부된다 — 1회성")
		void 같은_토큰의_두번째_사용은_거부된다() {
			String token = newToken();
			long userId = newUserId();
			store.save(token, userId);

			assertThat(store.consume(token)).isEqualTo(userId);
			assertThat(store.consume(token)).isNull();
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("발급된 적 없는 토큰의 소비는 null 이다")
		void 위조_토큰의_소비는_null이다() {
			assertThat(store.consume(newToken())).isNull();
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("폐기하면 남아 있던 링크가 죽는다 — 비밀번호 변경 성공이 부르는 경로")
		void 폐기하면_남은_토큰이_소비되지_않는다() {
			String token = newToken();
			long userId = newUserId();
			store.save(token, userId);

			store.revoke(userId);

			assertThat(store.consume(token)).isNull();
			assertThat(redisTemplate.hasKey(reverseKey(userId))).isFalse();
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("소비한 토큰을 복원하면 다시 쓸 수 있다 — 저장 실패 경로")
		void 토큰_소비_후_저장_실패면_토큰이_복원된다() {
			String token = newToken();
			long userId = newUserId();
			store.save(token, userId);
			store.consume(token);

			store.restore(token, userId);

			assertThat(store.consume(token)).isEqualTo(userId);
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("새 토큰이 발급돼 있으면 복원이 덮어쓰지 않는다 — compare-and-restore")
		void 새_토큰이_발급돼_있으면_복원이_덮어쓰지_않는다() {
			String consumed = newToken();
			String reissued = newToken();
			long userId = newUserId();
			store.save(consumed, userId);
			store.consume(consumed);
			store.save(reissued, userId);

			store.restore(consumed, userId);

			assertThat(store.consume(consumed)).isNull();
			assertThat(store.consume(reissued)).isEqualTo(userId);
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("소비가 교체된 토큰의 역방향을 지우지 않는다 — compare-and-delete")
		void 소비가_교체된_토큰의_역방향을_지우지_않는다() {
			String old = newToken();
			String current = newToken();
			long userId = newUserId();
			store.save(current, userId);
			// 교체로 죽었어야 할 옛 정방향 키를 되살려 "역방향은 새 토큰을 가리키는" 상태를 만든다
			redisTemplate.opsForValue().set(forwardKey(old), String.valueOf(userId), Duration.ofMinutes(30));

			assertThat(store.consume(old)).isEqualTo(userId);

			assertThat(redisTemplate.opsForValue().get(reverseKey(userId))).isEqualTo(sha256Hex(current));
			assertThat(store.consume(current)).isEqualTo(userId);
		}
	}

	@Nested
	@DisplayName("동시성·쿨다운")
	class ConcurrencyAndCooldown {

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("교체와 소비가 동시에 돌아도 폐기 불가 링크가 남지 않는다 — Lua 원자화")
		void 교체와_소비가_동시에_돌아도_두_키가_어긋나지_않는다() throws Exception {
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				for (int i = 0; i < 100; i++) {
					String first = newToken();
					String second = newToken();
					long userId = newUserId();
					store.save(first, userId);

					CyclicBarrier barrier = new CyclicBarrier(2);
					Future<?> replace = executor.submit(() -> {
						barrier.await();
						store.save(second, userId);
						return null;
					});
					Future<?> consume = executor.submit(() -> {
						barrier.await();
						store.consume(first);
						return null;
					});
					replace.get();
					consume.get();

					// 폐기는 역방향 하나만 보고 도는데, 두 명령이 원자가 아니면 역방향이 가리키지 않는
					// 정방향 키가 남아(폐기 불가 링크) 아래 소비가 성공해 버린다.
					store.revoke(userId);
					assertThat(store.consume(first)).isNull();
					assertThat(store.consume(second)).isNull();
				}
			} finally {
				executor.shutdownNow();
			}
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("쿨다운을 선점하면 60초 안의 재요청은 거부된다")
		void 쿨다운을_선점하면_다음_요청은_거부된다() {
			String email = "cooldown-" + UUID.randomUUID() + "@fillmap.dev";

			assertThat(store.tryAcquireCooldown(email)).isTrue();
			assertThat(store.tryAcquireCooldown(email)).isFalse();
			assertThat(store.tryAcquireCooldown("other-" + email)).isTrue();
		}
	}
}
