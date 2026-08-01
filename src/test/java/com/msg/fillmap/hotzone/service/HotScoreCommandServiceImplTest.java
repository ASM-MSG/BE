package com.msg.fillmap.hotzone.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

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
	void 워커_실행이_버킷_경계를_넘겨_지연돼도_호출_시각_버킷에_기록된다() {
		AtomicReference<Instant> now = new AtomicReference<>(FIXED_INSTANT);
		Clock mutableClock = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		List<Runnable> deferred = new ArrayList<>();
		HotScoreCommandServiceImpl delayedService =
			new HotScoreCommandServiceImpl(redisTemplate, mutableClock, deferred::add);

		delayedService.recordUpload(GRID_ID);
		now.set(FIXED_INSTANT.plusSeconds(BUCKET_SECONDS));   // 워커 실행 전에 6h 경계를 넘긴다
		deferred.forEach(Runnable::run);

		assertThat(redisTemplate.opsForZSet().score(BUCKET_KEY, GRID_ID)).isEqualTo(1.0);
		assertThat(redisTemplate.opsForZSet().score(NEXT_BUCKET_KEY, GRID_ID)).isNull();
	}

	@Test
	void 큐가_포화되면_예외_없이_신호를_버린다() {
		HotScoreCommandServiceImpl saturatedService = new HotScoreCommandServiceImpl(
			redisTemplate, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
			task -> {
				throw new RejectedExecutionException("큐 포화");
			});

		assertThatCode(() -> saturatedService.recordUpload(GRID_ID)).doesNotThrowAnyException();

		assertThat(redisTemplate.opsForZSet().score(BUCKET_KEY, GRID_ID)).isNull();
	}

	@Test
	void 종료_드레인_타임아웃이면_강제_종료한다() throws InterruptedException {
		ExecutorService stuckExecutor = mock(ExecutorService.class);
		when(stuckExecutor.awaitTermination(anyLong(), any())).thenReturn(false);
		when(stuckExecutor.shutdownNow()).thenReturn(List.of());
		HotScoreCommandServiceImpl stuckService = new HotScoreCommandServiceImpl(
			redisTemplate, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), stuckExecutor);

		stuckService.shutdown();

		verify(stuckExecutor).shutdownNow();
	}

	@Test
	void 종료_대기_중_인터럽트되면_플래그를_복원하고_강제_종료한다() throws InterruptedException {
		ExecutorService interruptedExecutor = mock(ExecutorService.class);
		when(interruptedExecutor.awaitTermination(anyLong(), any())).thenThrow(new InterruptedException());
		when(interruptedExecutor.shutdownNow()).thenReturn(List.of());
		HotScoreCommandServiceImpl interruptedService = new HotScoreCommandServiceImpl(
			redisTemplate, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), interruptedExecutor);

		interruptedService.shutdown();

		assertThat(Thread.interrupted()).isTrue();   // 복원 단언 겸 플래그 클리어 — 후속 테스트 오염 방지
		verify(interruptedExecutor).shutdownNow();
	}

	@Test
	void ExecutorService가_아닌_Executor면_종료는_아무_동작도_하지_않는다() {
		assertThatCode(service::shutdown).doesNotThrowAnyException();
	}

	@Test
	void 기본_생성자로_만든_서비스는_종료_시_큐를_드레인하고_끝난다() {
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
		deadFactory.afterPropertiesSet();
		StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
		deadTemplate.afterPropertiesSet();
		HotScoreCommandServiceImpl defaultService = new HotScoreCommandServiceImpl(deadTemplate);

		// 죽은 포트 템플릿 — 워커 스레드는 실제로 생성·실행되지만 실서비스 버킷 키를 오염시키지 않는다
		defaultService.recordUpload(GRID_ID);

		assertThatCode(defaultService::shutdown).doesNotThrowAnyException();

		deadFactory.destroy();
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
