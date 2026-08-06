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
 * "밀리면 스레드 수 증설"이라는 판단만 주석으로 남아 있다. 그 "밀리는" 지점을 잰다.
 *
 * <p>포화 실험은 로그를 끈 것과 켠 것을 나눠 잰다. 운영 코드는 폐기 건마다 예외 스택을 포함한
 * WARN 을 호출 스레드에서 남기므로, 로그를 끈 수치는 워커 자체의 상한일 뿐 운영에서 실제로
 * 겪는 값이 아니다. 두 값의 차이가 곧 그 로깅이 포화 상황에 얹는 비용이다.
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
	private static Logger serviceLogger;
	private static Level originalLevel;

	@BeforeAll
	static void setUpRedis() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();

		serviceLogger = (Logger) LoggerFactory.getLogger(HotScoreCommandServiceImpl.class);
		originalLevel = serviceLogger.getLevel();
	}

	@AfterAll
	static void tearDownRedis() {
		// 레벨을 되돌리지 않으면 같은 JVM 에서 이어 도는 다른 테스트의 로그까지 사라진다.
		serviceLogger.setLevel(originalLevel);
		redisTemplate.delete(BUCKET_KEY);
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("워커 처리율 — 큐 용량 이내를 한꺼번에 밀어넣고 드레인까지의 시간")
	void 워커_처리율을_측정한다() throws Exception {
		int signals = 5_000;   // 큐 용량(10,000) 이내라 폐기 없이 전량 처리된다
		redisTemplate.delete(BUCKET_KEY);
		serviceLogger.setLevel(Level.OFF);   // 정상 경로엔 로그가 없다 — 폐기 로그만 차단

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
		double stored = totalScore();

		System.out.printf("%n=== 워커 처리율 (큐 이내, 폐기 없음) ===%n");
		System.out.printf("  신호 %,d건%n", signals);
		System.out.printf("  적재(호출 스레드)  %8.1f ms  → 건당 %.4f ms%n", enqueueMs, enqueueMs / signals);
		System.out.printf("  드레인 완료        %8.1f ms  → %,.0f 건/초%n", drainMs, signals / (drainMs / 1000));
		System.out.printf("  Redis 격자 %d종 · 총 스코어 %.0f%n", redisTemplate.opsForZSet().zCard(BUCKET_KEY), stored);

		// 큐 용량 이내이므로 한 건도 잃지 않아야 한다. 이걸 안 보면 절반이 폐기돼도 통과한다.
		assertThat(stored).isEqualTo(signals);
		// 적재는 큐에 넣기만 하므로 드레인보다 훨씬 빨라야 한다 — D6 "응답 지연 분리"의 근거다.
		assertThat(enqueueMs).isLessThan(drainMs);
	}

	@Test
	@DisplayName("큐 포화 — 로그를 끈 워커 자체의 상한")
	void 큐_포화점을_측정한다_로그_없음() throws Exception {
		serviceLogger.setLevel(Level.OFF);
		Saturation result = runBurst(60_000);

		System.out.printf("%n=== 큐 포화 · 로그 OFF (워커 자체 상한) ===%n");
		printSaturation(result, 60_000);
		System.out.printf("  ※ 운영은 폐기마다 WARN 을 남기므로 이 값이 그대로 나오지 않는다%n");

		assertThat(result.lost).isGreaterThan(0);        // 실제로 포화됐는지
		assertThat(result.stored).isGreaterThan(0);      // Redis 기록이 실제로 일어났는지
	}

	@Test
	@DisplayName("큐 포화 — 폐기 로그까지 포함한 운영 조건")
	void 큐_포화점을_측정한다_운영_로그_포함() throws Exception {
		// 운영 기본값은 WARN 이다. 폐기 건마다 예외 스택이 호출 스레드에서 기록된다.
		serviceLogger.setLevel(Level.WARN);
		// 로그가 실제로 쏟아지는 조건이라 60,000건이면 측정보다 로그 처리가 오래 걸린다.
		// 20,000건이면 큐(10,000)를 확실히 넘기면서도 실행 시간이 감당된다.
		Saturation result = runBurst(20_000);
		serviceLogger.setLevel(Level.OFF);

		System.out.printf("%n=== 큐 포화 · 로그 ON (운영 조건) ===%n");
		printSaturation(result, 20_000);
		System.out.printf("  ※ 폐기 %,d건이 각각 스택트레이스를 남긴다%n", result.lost);

		assertThat(result.lost).isGreaterThan(0);
	}

	private record Saturation(double enqueueMs, double drainMs, double stored, long lost) { }

	private Saturation runBurst(int signals) throws Exception {
		redisTemplate.delete(BUCKET_KEY);
		ThreadPoolExecutor executor = newWorkerExecutor();
		HotScoreCommandServiceImpl service = newService(executor);

		long start = System.nanoTime();
		for (int i = 0; i < signals; i++) {
			service.recordUpload("burst_" + (i % 500));
		}
		long enqueued = System.nanoTime();

		executor.shutdown();
		assertThat(executor.awaitTermination(180, TimeUnit.SECONDS)).isTrue();
		long drained = System.nanoTime();

		double stored = totalScore();
		return new Saturation((enqueued - start) / 1e6, (drained - start) / 1e6,
			stored, signals - (long) stored);
	}

	private void printSaturation(Saturation r, int signals) {
		System.out.printf("  버스트 %,d건%n", signals);
		System.out.printf("  적재 소요        %8.1f ms  → 유입률 %,.0f 건/초%n",
			r.enqueueMs, signals / (r.enqueueMs / 1000));
		System.out.printf("  전량 드레인      %8.1f ms  → 처리율 %,.0f 건/초%n",
			r.drainMs, r.stored / (r.drainMs / 1000));
		System.out.printf("  기록됨 %,.0f건 · 유실 %,d건 (%.1f%%)%n",
			r.stored, r.lost, r.lost * 100.0 / signals);
		System.out.printf("  큐 용량 %,d — 유입이 처리율을 넘는 동안 이 만큼만 버틴다%n", QUEUE_CAPACITY);
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
