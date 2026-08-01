package com.msg.fillmap.hotzone.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 실제 Redis(localhost:6379)를 사용하는 스토어 계층 테스트 — 로컬은 fillmap-local-redis,
 * CI 는 redis 서비스 컨테이너 (RedisRefreshTokenStoreTest 방식). 고정 Clock 을 과거 시각
 * (2000-01-01)으로 잡아 버킷 키가 실서비스·타 테스트 키와 겹치지 않게 한다.
 * Executor 는 same-thread(Runnable::run) 주입 — 비동기 플레이크 없이 결정적으로 단언한다.
 */
@DisplayName("HotScoreCommandServiceImpl")
class HotScoreCommandServiceImplTest {

	/** 2000-01-01T00:00:00Z — 실서비스(현재 epoch) 버킷 대역과 격리된 과거 시각. */
	private static final Instant FIXED_INSTANT = Instant.parse("2000-01-01T00:00:00Z");
	private static final long BUCKET_SECONDS = 21600L;
	private static final String BUCKET_KEY = "hotzone:" + FIXED_INSTANT.getEpochSecond() / BUCKET_SECONDS;
	private static final String NEXT_BUCKET_KEY = "hotzone:" + (FIXED_INSTANT.getEpochSecond() / BUCKET_SECONDS + 1);
	private static final String GRID_ID = "41642_110458";

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static HotScoreCommandServiceImpl service;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		service = new HotScoreCommandServiceImpl(redisTemplate, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
			Runnable::run);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(List.of(BUCKET_KEY, NEXT_BUCKET_KEY));
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	@Test
	void 업로드_신호_1건은_현재_버킷의_해당_격자_핫스코어를_1_올린다() {
		service.recordUpload(GRID_ID);

		assertThat(redisTemplate.opsForZSet().score(BUCKET_KEY, GRID_ID)).isEqualTo(1.0);
	}

	@Test
	void 같은_격자에_신호가_반복되면_핫스코어가_누적된다() {
		service.recordUpload(GRID_ID);
		service.recordUpload(GRID_ID);
		service.recordUpload(GRID_ID);

		assertThat(redisTemplate.opsForZSet().score(BUCKET_KEY, GRID_ID)).isEqualTo(3.0);
	}

	@Test
	void 버킷_키에_54시간_TTL이_설정된다() {
		service.recordUpload(GRID_ID);

		Long expireSeconds = redisTemplate.getExpire(BUCKET_KEY);

		assertThat(expireSeconds).isBetween(Duration.ofHours(53).toSeconds(), Duration.ofHours(54).toSeconds());
	}

	@Test
	void 버킷이_바뀌면_다른_키에_기록된다() {
		Instant nextBucketInstant = FIXED_INSTANT.plusSeconds(BUCKET_SECONDS);
		HotScoreCommandServiceImpl nextBucketService =
			new HotScoreCommandServiceImpl(redisTemplate, Clock.fixed(nextBucketInstant, ZoneOffset.UTC),
				Runnable::run);

		service.recordUpload(GRID_ID);
		nextBucketService.recordUpload(GRID_ID);

		assertThat(redisTemplate.opsForZSet().score(BUCKET_KEY, GRID_ID)).isEqualTo(1.0);
		assertThat(redisTemplate.opsForZSet().score(NEXT_BUCKET_KEY, GRID_ID)).isEqualTo(1.0);
	}

	@Test
	void Redis_장애에도_예외를_전파하지_않는다() {
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
		deadFactory.afterPropertiesSet();
		StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
		deadTemplate.afterPropertiesSet();
		HotScoreCommandServiceImpl deadService =
			new HotScoreCommandServiceImpl(deadTemplate, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
				Runnable::run);

		assertThatCode(() -> deadService.recordUpload(GRID_ID)).doesNotThrowAnyException();

		deadFactory.destroy();
	}
}
