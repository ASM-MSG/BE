package com.msg.fillmap.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 실제 Redis(localhost:6379)를 사용하는 블랙리스트 테스트 — 짧은 TTL 로 등록해 키가 자연 소멸한다.
 */
@DisplayName("RedisInvalidatedTokenStore")
class RedisInvalidatedTokenStoreTest {

	private static LettuceConnectionFactory connectionFactory;
	private static RedisInvalidatedTokenStore store;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		store = new RedisInvalidatedTokenStore(redisTemplate);
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	private static String newToken() {
		return "test-access-token-" + UUID.randomUUID();
	}

	@Test
	void 블랙리스트에_올린_액세스_토큰은_isInvalidated가_true다() {
		String token = newToken();

		store.invalidate(token, Instant.now().plus(Duration.ofSeconds(5)));

		assertThat(store.isInvalidated(token)).isTrue();
	}

	@Test
	void 등록하지_않은_토큰은_isInvalidated가_false다() {
		assertThat(store.isInvalidated(newToken())).isFalse();
	}

	@Test
	void 잔여만료가_지난_토큰은_자동_만료되어_false다() throws InterruptedException {
		String token = newToken();
		store.invalidate(token, Instant.now().plusMillis(300));

		Thread.sleep(600);

		assertThat(store.isInvalidated(token)).isFalse();
	}
}
