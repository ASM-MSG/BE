package com.msg.fillmap.hotzone.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * 핫스코어 집계 워커(MSG-183)의 처리율·큐 포화점 실측 — 부하 실험용이라 일반 테스트에서는 돈다.
 * {@code HotScoreCommandServiceImpl.defaultExecutor()} 는 단일 스레드 + 큐 10,000 이고
 * "밀리면 스레드 수 증설"이라는 판단만 주석으로 남아 있다. 그 "밀리는" 지점이 어디인지 잰다.
 *
 * <p>실행: {@code HOTZONE_BENCH=true ./gradlew test --tests '*HotScoreThroughputBenchmark'}
 * (실제 Redis 필요 — 로컬 fillmap-local-redis. 버킷 키는 과거 시각으로 격리한다.)
 */
@DisplayName("핫스코어 집계 워커 처리율 벤치마크")
@EnabledIfEnvironmentVariable(named = "HOTZONE_BENCH", matches = "true")
class HotScoreThroughputBenchmark {

	/** 2001-01-01T00:00:00Z — 실서비스·타 테스트 버킷과 겹치지 않는 과거 시각. */
	private static final Instant FIXED_INSTANT = Instant.parse("2001-01-01T00:00:00Z");
	private static final String BUCKET_KEY = "hotzone:" + FIXED_INSTANT.getEpochSecond() / 21600L;

	/** HotScoreCommandServiceImpl.defaultExecutor() 와 같은 형상 (private static 이라 복제). */
	private static final int QUEUE_CAPACITY = 10_000;

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;

	@BeforeAll
	static void setUpRedis() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();

		// 큐 포화 시 폐기 건마다 스택트레이스가 통째로 WARN 으로 찍힌다(recordUpload 의 catch).
		// 5만 건이면 로그가 벤치보다 오래 걸려 측정 자체가 불가능해진다 — 벤치 동안만 끈다.
		// (이 로그량 자체가 실측 결과의 하나다 — 운영에서 포화가 나면 같은 일이 벌어진다.)
		((Logger) LoggerFactory.getLogger(HotScoreCommandServiceImpl.class)).setLevel(Level.OFF);
	}

	@AfterAll
	static void tearDownRedis() {
		redisTemplate.delete(BUCKET_KEY);
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("워커 처리율 — 큐 용량 이내를 한꺼번에 밀어넣고 드레인까지의 시간")
	void 워커_처리율을_측정한다() throws Exception {
		int signals = 5_000;   // 큐 용량(10,000) 이내라 폐기 없이 전량 처리된다
		redisTemplate.delete(BUCKET_KEY);

		ThreadPoolExecutor executor = newWorkerExecutor();
		HotScoreCommandServiceImpl service = newService(executor);

		long start = System.nanoTime();
		for (int i = 0; i < signals; i++) {
			service.recordUpload("bench_" + (i % 500));   // 격자 500종 — ZINCRBY 대상이 흩어지게
		}
		long enqueued = System.nanoTime();

		executor.shutdown();
		assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
		long drained = System.nanoTime();

		double enqueueMs = (enqueued - start) / 1e6;
		double drainMs = (drained - start) / 1e6;
		long stored = redisTemplate.opsForZSet().zCard(BUCKET_KEY);

		System.out.printf("%n=== 워커 처리율 ===%n");
		System.out.printf("  신호 %,d건%n", signals);
		System.out.printf("  적재(호출 스레드)  %8.1f ms  → 건당 %.4f ms%n", enqueueMs, enqueueMs / signals);
		System.out.printf("  드레인 완료        %8.1f ms  → %,.0f 건/초%n", drainMs, signals / (drainMs / 1000));
		System.out.printf("  Redis 격자 %d종 · 총 스코어 %.0f%n", stored, totalScore());

		// 적재는 큐에 넣기만 하므로 드레인보다 훨씬 빨라야 한다 — 이게 D6 "응답 지연 분리"의 근거다.
		assertThat(enqueueMs).isLessThan(drainMs);
	}

	@Test
	@DisplayName("큐 포화점 — 처리율을 넘겨 밀어넣으면 몇 건째부터 유실되는가")
	void 큐_포화점을_측정한다() throws Exception {
		int signals = 60_000;   // 큐 용량의 6배 — 반드시 포화된다
		redisTemplate.delete(BUCKET_KEY);

		ThreadPoolExecutor executor = newWorkerExecutor();
		HotScoreCommandServiceImpl service = newService(executor);

		long start = System.nanoTime();
		for (int i = 0; i < signals; i++) {
			service.recordUpload("burst_" + (i % 500));
		}
		long enqueued = System.nanoTime();

		executor.shutdown();
		assertThat(executor.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
		long drained = System.nanoTime();

		double enqueueMs = (enqueued - start) / 1e6;
		double drainMs = (drained - start) / 1e6;
		double stored = totalScore();
		long lost = signals - (long) stored;

		System.out.printf("%n=== 큐 포화 (버스트 %,d건) ===%n", signals);
		System.out.printf("  적재 소요        %8.1f ms  → 유입률 %,.0f 건/초%n",
			enqueueMs, signals / (enqueueMs / 1000));
		System.out.printf("  전량 드레인      %8.1f ms  → 처리율 %,.0f 건/초%n",
			drainMs, stored / (drainMs / 1000));
		System.out.printf("  기록됨 %,.0f건 · 유실 %,d건 (%.1f%%)%n",
			stored, lost, lost * 100.0 / signals);
		System.out.printf("  큐 용량 %,d — 유입이 처리율을 넘는 동안 이 만큼만 버틴다%n", QUEUE_CAPACITY);

		// 유실은 설계상 허용이다(FR-6). 벤치의 목적은 "얼마나" 잃는지 수치를 남기는 것.
		assertThat(stored).isLessThanOrEqualTo(signals);
	}

	private double totalScore() {
		var tuples = redisTemplate.opsForZSet().rangeWithScores(BUCKET_KEY, 0, -1);
		if (tuples == null) {
			return 0;
		}
		return tuples.stream().mapToDouble(t -> t.getScore() == null ? 0 : t.getScore()).sum();
	}

	private HotScoreCommandServiceImpl newService(ThreadPoolExecutor executor) {
		return new HotScoreCommandServiceImpl(
			redisTemplate, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), executor);
	}

	private static ThreadPoolExecutor newWorkerExecutor() {
		return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(QUEUE_CAPACITY),
			runnable -> {
				Thread thread = new Thread(runnable, "bench-hotzone-score");
				thread.setDaemon(true);
				return thread;
			});
	}
}
