package com.msg.fillmap.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 실제 Redis(localhost:6379)를 사용하는 스토어 계층 테스트 — 로컬은 fillmap-local-redis,
 * CI 는 redis 서비스 컨테이너. 키 충돌을 피하려고 테스트 전용 userId 대역을 쓴다.
 */
@DisplayName("RedisRefreshTokenStore")
class RedisRefreshTokenStoreTest {

	private static final Long USER_ID = 913500001L;
	private static final Long OTHER_USER_ID = 913500002L;
	private static final Duration TTL = Duration.ofDays(14);

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static RedisRefreshTokenStore store;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		store = new RedisRefreshTokenStore(redisTemplate);
	}

	@AfterEach
	void tearDown() {
		store.deleteAll(USER_ID);
		store.deleteAll(OTHER_USER_ID);
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	@Test
	void 저장한_jti를_userId_deviceId로_조회한다() {
		store.save(USER_ID, "device-a", "jti-1", TTL);

		assertThat(store.findJti(USER_ID, "device-a")).isEqualTo("jti-1");
	}

	@Test
	void 삭제하면_조회결과가_null이다() {
		store.save(USER_ID, "device-a", "jti-1", TTL);

		store.delete(USER_ID, "device-a");

		assertThat(store.findJti(USER_ID, "device-a")).isNull();
	}

	@Test
	void 같은_유저의_다른_deviceId는_서로_독립적으로_저장_삭제된다() {
		store.save(USER_ID, "device-a", "jti-a", TTL);
		store.save(USER_ID, "device-b", "jti-b", TTL);

		store.delete(USER_ID, "device-a");

		assertThat(store.findJti(USER_ID, "device-a")).isNull();
		assertThat(store.findJti(USER_ID, "device-b")).isEqualTo("jti-b");
	}

	@Test
	void TTL_2주가_설정된다() {
		store.save(USER_ID, "device-a", "jti-1", TTL);

		Long expireSeconds = redisTemplate.getExpire("refresh:" + USER_ID + ":device-a");

		assertThat(expireSeconds).isBetween(Duration.ofDays(13).toSeconds(), Duration.ofDays(14).toSeconds());
	}

	@Test
	void 로그아웃올_폴백은_해당_유저의_모든_디바이스_세션만_삭제한다() {
		store.save(USER_ID, "device-a", "jti-a", TTL);
		store.save(USER_ID, "device-b", "jti-b", TTL);
		store.save(OTHER_USER_ID, "device-a", "jti-x", TTL);

		store.deleteAll(USER_ID);

		assertThat(store.findJti(USER_ID, "device-a")).isNull();
		assertThat(store.findJti(USER_ID, "device-b")).isNull();
		assertThat(store.findJti(OTHER_USER_ID, "device-a")).isEqualTo("jti-x");
	}
}
