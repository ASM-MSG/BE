package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import jakarta.persistence.EntityManagerFactory;


import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.msg.fillmap.event.repository.EventNotificationSubscriptionRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;

/**
 * 임시 로컬 감사 벤치. 별도 fillmap_tx_bench_* DB에서만 실행합니다.
 * 테스트 외곽 @Transactional 없음: 실제 scheduler의 발송/정리 두 커밋을 측정합니다.
 * 실행 전 root가 datasource를 별도 DB로 지정하고 모든 시더/AI/워커를 끕니다.
 * 출력 FANOUT_SAMPLE은 fixture 적재, 삭제, ANALYZE, 검증 SELECT 시간을 제외합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"fillmap.notification.enabled=false",
	"fillmap.event.lifecycle.poll-interval-ms=86400000",
	"spring.jpa.properties.hibernate.generate_statistics=true",
	"logging.level.org.hibernate.stat=OFF",
	"logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class EventFanoutBenchmark {

	private static final LocalDateTime START = LocalDateTime.now(ZoneOffset.UTC).withNano(0).minusMinutes(1);
	private static final Clock CLOCK = Clock.fixed(START.plusMinutes(1).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
	private static final int[] SIZES = java.util.Arrays.stream(System.getenv()
		.getOrDefault("BENCH_FANOUT_SIZES", "1000,10000,50000").split(","))
		.mapToInt(Integer::parseInt).toArray();

	@org.springframework.boot.test.web.server.LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private EventOccurrenceRepository occurrenceRepository;
	@Autowired
	private EventNotificationSubscriptionRepository subscriptionRepository;
	@Autowired
	private EventSeriesRepository seriesRepository;
	@Autowired
	private NotificationCommandService notificationCommandService;
	@Autowired
	private PlatformTransactionManager txManager;
	@Autowired
	private EntityManagerFactory entityManagerFactory;
	@Autowired
	private MeterRegistry meterRegistry;
	@Autowired
	private ScheduledAnnotationBeanPostProcessor schedules;

	@Test
	void benchmarkActualSchedulerFanout() {
		schedules.getScheduledTasks().forEach(task -> task.cancel(false));
		String database = jdbc.queryForObject("SELECT current_database()", String.class);
		assertThat(database).as("벤치는 별도 감사 DB에서만 실행합니다").startsWith("fillmap_tx_bench_");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM event_occurrences", Long.class))
			.as("다른 회차를 scheduler.tick이 갱신하지 않도록 비어 있는 행사 테이블을 요구합니다").isZero();
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		assertThat(stats.isStatisticsEnabled()).isTrue();
		System.out.printf("FANOUT_ENV database=%s jvm=%s max_heap_bytes=%d processors=%d%n",
			database, System.getProperty("java.version"), Runtime.getRuntime().maxMemory(),
			Runtime.getRuntime().availableProcessors());

		for (int size : SIZES) {
			String tag = "txfan-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
			String emailPattern = tag + "-%@example.invalid";
			try {
				long occurrenceId = createFixture(tag, emailPattern, size);
				ObservedTransactionManager observed = new ObservedTransactionManager(txManager);
				EventNotificationScheduler scheduler = new EventNotificationScheduler(
					occurrenceRepository, subscriptionRepository, seriesRepository,
					benchmarkCommand(), observed, true, CLOCK);
				String eventKey = "EVENT_START:" + tag + ":" + START.toEpochSecond(ZoneOffset.UTC);
				if (size >= 50_000 && !Boolean.parseBoolean(System.getenv("BENCH_REPEAT_LARGE"))) {
					// 기존 5만 명 단발 기준값과 비교하도록 반복 횟수를 유지합니다.
					measure(scheduler, observed, stats, occurrenceId, eventKey, size, "LARGE_SINGLE", 1);
					continue;
				}
				measure(scheduler, observed, stats, occurrenceId, eventKey, size, "WARMUP_FRESH", 0);
				for (int repetition = 1; repetition <= 3; repetition++) {
					jdbc.update("DELETE FROM notifications WHERE event_key = ?", eventKey);
					assertThat(notificationCount(eventKey)).isZero();
					measure(scheduler, observed, stats, occurrenceId, eventKey, size, "FRESH", repetition);
				}
				// 이미 기록된 구독자의 중복 확인 비용을 따로 1회 기록합니다.
				measure(scheduler, observed, stats, occurrenceId, eventKey, size, "DEDUPE", 1);
			} finally {
				// 자기 자연키만 정리합니다. TRUNCATE나 다른 벤치 데이터 삭제는 없습니다.
				jdbc.update("DELETE FROM users WHERE email LIKE ?", emailPattern);
				jdbc.update("DELETE FROM event_occurrences WHERE occurrence_key = ?", tag);
				jdbc.update("DELETE FROM event_series WHERE series_key = ?", tag);
				assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email LIKE ?", Long.class,
					emailPattern)).isZero();
			}
		}
	}


	/** 별도 경쟁 시나리오: 차단 확인까지 기록을 보류하므로 순수 성능 샘플과 합산하지 않습니다. */
	@Test
	void benchmarkEventWriteLockContentionDuringFiftyThousandFanout() throws Exception {
		schedules.getScheduledTasks().forEach(task -> task.cancel(false));
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).startsWith("fillmap_tx_bench_");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM event_occurrences", Long.class)).isZero();
		String tag = "txlock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String emailPattern = tag + "-%@example.invalid";
		String eventKey = "EVENT_START:" + tag + ":" + START.toEpochSecond(ZoneOffset.UTC);
		HikariDataSource source = jdbc.getDataSource().unwrap(HikariDataSource.class);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		CountDownLatch allowInsert = new CountDownLatch(1);
		try (Connection observer = DriverManager.getConnection(
				source.getJdbcUrl(), source.getUsername(), source.getPassword());
			Connection competitor = DriverManager.getConnection(
				source.getJdbcUrl(), source.getUsername(), source.getPassword())) {
			createFixture(tag, emailPattern, 50_000);
			try (Statement settings = observer.createStatement()) {
				settings.execute("SET statement_timeout = '5s'");
			}
			int waiterPid;
			try (Statement identity = competitor.createStatement();
				ResultSet row = identity.executeQuery("SELECT pg_backend_pid()")) {
				row.next();
				waiterPid = row.getInt(1);
			}
			competitor.setAutoCommit(false);
			try (Statement settings = competitor.createStatement()) {
				settings.execute("SET LOCAL lock_timeout = '30s'");
				settings.execute("SET LOCAL statement_timeout = '35s'");
			}
			CountDownLatch firstRecord = new CountDownLatch(1);
			NotificationCommandService observedCommand = new NotificationCommandService() {
				@Override
				public void record(Long userId, NotificationCategory category, String key, String title, String body) {
					notificationCommandService.record(userId, category, key, title, body);
				}

				@Override
				public void recordEventStart(long occurrenceId, LocalDateTime startsAt,
					String key, String title, String body) {
					firstRecord.countDown();
					try {
						assertThat(allowInsert.await(15, TimeUnit.SECONDS)).isTrue();
					} catch (InterruptedException error) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(error);
					}
					notificationCommandService.recordEventStart(occurrenceId, startsAt, key, title, body);
				}
			};
			ObservedTransactionManager observedTx = new ObservedTransactionManager(txManager, jdbc);
			EventNotificationScheduler scheduler = new EventNotificationScheduler(
				occurrenceRepository, subscriptionRepository, seriesRepository,
				observedCommand, observedTx, true, CLOCK);
			Future<?> fanout = workers.submit(scheduler::tick);
			assertThat(firstRecord.await(30, TimeUnit.SECONDS))
				.as("첫 production record 진입을 기다립니다").isTrue();

			int holderPid;
			// bigint advisory 키의 두 32-bit 절반을 pg_locks와 정확히 대응합니다.
			try (Statement query = observer.createStatement(); ResultSet row = query.executeQuery("""
				SELECT pid FROM pg_locks
				WHERE locktype = 'advisory' AND granted AND objsubid = 1
				  AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
				  AND classid = ((hashtextextended('event_seed', 0) >> 32) & 4294967295)::oid
				  AND objid = (hashtextextended('event_seed', 0) & 4294967295)::oid
				""")) {
				assertThat(row.next()).as("발송 TX가 실제 행사 잠금을 보유해야 합니다").isTrue();
				holderPid = row.getInt(1);
				assertThat(row.next()).as("잠금 보유자는 하나여야 합니다").isFalse();
			}
			Future<Double> waiting = workers.submit(() -> {
				try (Statement lock = competitor.createStatement()) {
					long start = System.nanoTime();
					lock.execute("SELECT pg_advisory_xact_lock(hashtextextended('event_seed', 0))");
					double waitMs = (System.nanoTime() - start) / 1_000_000.0;
					competitor.commit();
					return waitMs;
				} catch (Exception error) {
					competitor.rollback();
					throw error;
				}
			});
			boolean blocked = false;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (!waiting.isDone() && System.nanoTime() < deadline) {
				try (Statement query = observer.createStatement(); ResultSet row = query.executeQuery(
					"SELECT " + holderPid + " = ANY(pg_blocking_pids(" + waiterPid + "))")) {
					row.next();
					blocked = row.getBoolean(1);
				}
				if (blocked) {
					break;
				}
				// 조건부 짧은 재조회 간격입니다. 고정 시간 sleep으로 경합을 추정하지 않습니다.
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
			}
			allowInsert.countDown();
			double waitMs = waiting.get(40, TimeUnit.SECONDS);
			fanout.get(40, TimeUnit.SECONDS);
			assertThat(blocked).as("pg_blocking_pids에서 실제 fanout TX에 의한 대기를 확인합니다").isTrue();
			assertThat(notificationCount(eventKey)).isEqualTo(50_000);
			assertThat(observedTx.samples).hasSize(2);
			assertThat(observedTx.samples.stream().allMatch(TxSample::committed)).isTrue();
			System.out.printf(Locale.ROOT,
				"FANOUT_LOCK_SAMPLE mode=CONTROLLED size=50000 holder_pid=%d waiter_pid=%d blocked_confirmed=%s "
					+ "lock_acquire_statement_ms=%.3f fanout_tx_envelope_ms=%.3f notifications=50000%n",
				holderPid, waiterPid, blocked, waitMs, observedTx.samples.get(0).elapsedMs());
		} finally {
			// JDBC 대기는 위 timeout으로 제한됩니다. 자기 워커가 끝난 뒤에만 fixture를 삭제합니다.
			allowInsert.countDown();
			workers.shutdownNow();
			boolean stopped = workers.awaitTermination(40, TimeUnit.SECONDS);
			if (stopped) {
				jdbc.update("DELETE FROM users WHERE email LIKE ?", emailPattern);
				jdbc.update("DELETE FROM event_occurrences WHERE occurrence_key = ?", tag);
				jdbc.update("DELETE FROM event_series WHERE series_key = ?", tag);
			}
			assertThat(stopped).as("워커가 남으면 fixture를 삭제하지 않고 실행을 실패시킵니다").isTrue();
		}
	}


	/** 개선 전 70829e28의 recordStart 반복문을 그대로 재현하는 대조군입니다. */
	private NotificationCommandService benchmarkCommand() {
		if (!"legacy".equals(System.getenv("BENCH_IMPLEMENTATION"))) {
			return notificationCommandService;
		}
		return new NotificationCommandService() {
			@Override
			public void record(Long userId, NotificationCategory category, String key, String title, String body) {
				notificationCommandService.record(userId, category, key, title, body);
			}

			@Override
			public void recordEventStart(long occurrenceId, LocalDateTime startsAt,
				String key, String title, String body) {
				for (var subscription : subscriptionRepository.findAllByIdEventOccurrenceId(occurrenceId)) {
					if (!subscription.getCreatedAt().isAfter(startsAt)) {
						notificationCommandService.record(subscription.getId().getUserId(),
							NotificationCategory.EVENT, key, title, body);
					}
				}
			}
		};
	}

	/** 고정 10초 창의 실제 HTTP 혼합 부하. 두 클라이언트 closed-loop, think time 10ms입니다. */
	@Test
	void benchmarkConcurrentHttpDuringFanout() throws Exception {
		schedules.getScheduledTasks().forEach(task -> task.cancel(false));
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).startsWith("fillmap_tx_bench_");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM event_occurrences", Long.class)).isZero();
		for (int repetition = 0; repetition < 4; repetition++) {
			String tag = "mixed-" + UUID.randomUUID().toString().substring(0, 12);
			String pattern = tag + "-%@example.invalid";
			String key = "EVENT_START:" + tag + ":" + START.toEpochSecond(ZoneOffset.UTC);
			ExecutorService workers = Executors.newFixedThreadPool(3);
			try (var client = java.net.http.HttpClient.newHttpClient()) {
				long occurrenceId = createFixture(tag, pattern, 10000);
				var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
					"http://localhost:" + port + "/api/event-occurrences/" + occurrenceId))
					.timeout(java.time.Duration.ofSeconds(30)).build();
				var warmup = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
				assertThat(warmup.statusCode()).isEqualTo(200);
				assertThat(warmup.body()).contains("감사 합성 행사");
				CountDownLatch gate = new CountDownLatch(1);
				List<Future<List<Double>>> readers = new ArrayList<>();
				var errors = new java.util.concurrent.atomic.AtomicInteger();
				for (int reader = 0; reader < 2; reader++) {
					readers.add(workers.submit(() -> {
						assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
						List<Double> times = new ArrayList<>();
						long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
						while (System.nanoTime() < until) {
							long start = System.nanoTime();
							try {
								var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
								if (response.statusCode() == 200 && response.body().contains("감사 합성 행사")) {
									times.add((System.nanoTime() - start) / 1_000_000.0);
								} else {
									errors.incrementAndGet();
								}
							} catch (java.io.IOException failure) {
								errors.incrementAndGet();
								System.out.printf("MIXED_HTTP_ERROR type=%s elapsed_ms=%.3f%n",
									failure.getClass().getSimpleName(), (System.nanoTime() - start) / 1_000_000.0);
							}
							Thread.sleep(10);
						}
						return times;
					}));
				}
				var scheduler = new EventNotificationScheduler(occurrenceRepository, subscriptionRepository,
					seriesRepository, benchmarkCommand(), txManager, true, CLOCK);
				long start = System.nanoTime();
				gate.countDown();
				scheduler.tick();
				double tickMs = (System.nanoTime() - start) / 1_000_000.0;
				System.out.printf("MIXED_TICK repetition=%d elapsed_ms=%.3f%n", repetition, tickMs);
				List<Double> times = new ArrayList<>();
				for (var reader : readers) {
					times.addAll(reader.get(45, TimeUnit.SECONDS));
				}
				times.sort(Double::compareTo);
				assertThat(notificationCount(key)).isEqualTo(10000);
				System.out.printf(Locale.ROOT,
					"MIXED_SAMPLE implementation=%s repetition=%d tick_ms=%.3f requests=%d "
						+ "errors=%d p50_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
					System.getenv().getOrDefault("BENCH_IMPLEMENTATION", "bulk"), repetition, tickMs,
					times.size() + errors.get(), errors.get(),
					times.isEmpty() ? Double.NaN : times.get((int)Math.ceil(times.size() * .5) - 1),
					times.isEmpty() ? Double.NaN : times.get((int)Math.ceil(times.size() * .95) - 1),
					times.isEmpty() ? Double.NaN : times.getLast());
			} finally {
				workers.shutdownNow();
				assertThat(workers.awaitTermination(40, TimeUnit.SECONDS)).isTrue();
				jdbc.update("DELETE FROM users WHERE email LIKE ?", pattern);
				jdbc.update("DELETE FROM event_occurrences WHERE occurrence_key = ?", tag);
				jdbc.update("DELETE FROM event_series WHERE series_key = ?", tag);
			}
		}
	}

	/** 고정 유입률 시험. 완료를 기다려 다음 요청을 늦추지 않고 예정 시각부터 지연을 계산합니다. */
	@Test
	void benchmarkFixedArrivalHttpDuringFanout() throws Exception {
		schedules.getScheduledTasks().forEach(task -> task.cancel(false));
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
			.isEqualTo("fillmap_tx_bench_20260917");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM event_occurrences", Long.class)).isZero();
		int rate = Integer.parseInt(System.getenv().getOrDefault("BENCH_HTTP_RATE", "200"));
		assertThat(rate).isBetween(1, 500);
		int count = rate * 10;
		for (int repetition = 0; repetition < 4; repetition++) {
			String tag = "arrival-" + UUID.randomUUID().toString().substring(0, 12);
			String pattern = tag + "-%@example.invalid";
			String key = "EVENT_START:" + tag + ":" + START.toEpochSecond(ZoneOffset.UTC);
			ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
			try (var client = java.net.http.HttpClient.newHttpClient()) {
				long occurrenceId = createFixture(tag, pattern, 10000);
				var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
					"http://localhost:" + port + "/api/event-occurrences/" + occurrenceId))
					.timeout(java.time.Duration.ofSeconds(30)).build();
				for (int warmup = 0; warmup < 30; warmup++) {
					var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
					assertThat(response.statusCode()).isEqualTo(200);
					assertThat(response.body()).contains("감사 합성 행사");
				}
				long epoch = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
				var errors = new java.util.concurrent.atomic.AtomicInteger();
				List<Future<double[]>> readers = new ArrayList<>();
				for (int i = 0; i < count; i++) {
					long due = epoch + i * TimeUnit.SECONDS.toNanos(1) / rate;
					readers.add(workers.submit(() -> {
						awaitNanos(due);
						long sent = System.nanoTime();
						try {
							var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
							if (response.statusCode() != 200 || !response.body().contains("감사 합성 행사")) {
								errors.incrementAndGet();
							}
						} catch (java.io.IOException failure) {
							errors.incrementAndGet();
						}
						return new double[] {(System.nanoTime() - due) / 1_000_000.0,
							(sent - due) / 1_000_000.0};
					}));
				}
				awaitNanos(epoch);
				long start = System.nanoTime();
				new EventNotificationScheduler(occurrenceRepository, subscriptionRepository,
					seriesRepository, benchmarkCommand(), txManager, true, CLOCK).tick();
				double tickMs = (System.nanoTime() - start) / 1_000_000.0;
				List<Double> times = new ArrayList<>();
				List<Double> dispatch = new ArrayList<>();
				for (var reader : readers) {
					var sample = reader.get(45, TimeUnit.SECONDS);
					times.add(sample[0]);
					dispatch.add(sample[1]);
				}
				times.sort(Double::compareTo);
				dispatch.sort(Double::compareTo);
				assertThat(notificationCount(key)).isEqualTo(10000);
				assertThat(times).hasSize(count);
				System.out.printf(Locale.ROOT,
					"ARRIVAL_SAMPLE implementation=%s rate=%d repetition=%d tick_ms=%.3f requests=%d "
						+ "errors=%d p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f max_ms=%.3f dispatch_p95_ms=%.3f%n",
					System.getenv().getOrDefault("BENCH_IMPLEMENTATION", "bulk"), rate, repetition, tickMs,
					count, errors.get(), times.get((int)Math.ceil(count * .5) - 1),
					times.get((int)Math.ceil(count * .95) - 1), times.get((int)Math.ceil(count * .99) - 1),
					times.getLast(), dispatch.get((int)Math.ceil(count * .95) - 1));
			} finally {
				workers.shutdownNow();
				assertThat(workers.awaitTermination(40, TimeUnit.SECONDS)).isTrue();
				jdbc.update("DELETE FROM users WHERE email LIKE ?", pattern);
				jdbc.update("DELETE FROM event_occurrences WHERE occurrence_key = ?", tag);
				jdbc.update("DELETE FROM event_series WHERE series_key = ?", tag);
			}
		}
	}

	private static void awaitNanos(long due) throws InterruptedException {
		long remaining;
		while ((remaining = due - System.nanoTime()) > 0) {
			LockSupport.parkNanos(remaining);
			if (Thread.interrupted()) {
				throw new InterruptedException();
			}
		}
	}

	private long createFixture(String tag, String emailPattern, int size) {
		Long seriesId = jdbc.queryForObject(
			"INSERT INTO event_series(series_key, name) VALUES (?, '감사 합성 시리즈') RETURNING id",
			Long.class, tag);
		Long occurrenceId = jdbc.queryForObject("""
			INSERT INTO event_occurrences(event_series_id, occurrence_key, title, city_name,
				starts_at, ends_at, visible_from, min_grid_y, max_grid_y, min_grid_x, max_grid_x)
			VALUES (?, ?, '감사 합성 행사', '부산', ?, ?, ?, 90000, 90001, 90500, 90501)
			RETURNING id
			""", Long.class, seriesId, tag, START, START.plusDays(10), START.minusDays(14));
		// ID를 한 번만 할당해 재사용합니다. friend_code는 8자 UNIQUE/NOT NULL을 만족합니다.
		// 합성 사용자는 로그인하지 않으므로 password_hash는 의도적으로 계산하지 않습니다.
		int users = jdbc.update("""
			WITH ids AS MATERIALIZED (
				SELECT nextval(pg_get_serial_sequence('users', 'id')) AS id, g
				FROM generate_series(1, ?) AS g
			)
			INSERT INTO users(id, provider, email, password_hash, nickname, friend_code)
			SELECT id, 'LOCAL', ? || '-' || g || '@example.invalid', 'benchmark-unused-hash',
				'감사 합성 사용자', upper(lpad(to_hex(id), 8, '0'))
			FROM ids
			""", size, tag);
		assertThat(users).isEqualTo(size);
		int subscribers = jdbc.update("""
			INSERT INTO event_notification_subscriptions(user_id, event_occurrence_id, created_at)
			SELECT id, ?, ? FROM users WHERE email LIKE ?
			""", occurrenceId, START.minusDays(1), emailPattern);
		assertThat(subscribers).isEqualTo(size);
		jdbc.execute("ANALYZE users");
		jdbc.execute("ANALYZE event_occurrences");
		jdbc.execute("ANALYZE event_notification_subscriptions");
		jdbc.execute("ANALYZE notifications");
		System.out.printf("FANOUT_FIXTURE tag=%s subscribers=%d occurrence_id=%d%n", tag, size, occurrenceId);
		return occurrenceId;
	}

	private void measure(EventNotificationScheduler scheduler, ObservedTransactionManager observed,
		Statistics stats, long occurrenceId, String eventKey, int size, String mode, int repetition) {
		observed.samples.clear();
		System.out.printf("FANOUT_BEGIN size=%d mode=%s repetition=%d%n", size, mode, repetition);
		long preparesBefore = stats.getPrepareStatementCount();
		PoolUsage usageBefore = poolUsage();
		long started = System.nanoTime();
		scheduler.tick();
		double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
		PoolUsage usageAfter = poolUsage();
		long prepares = stats.getPrepareStatementCount() - preparesBefore;

		// 스케줄러가 발송 예외를 잡아도, 이 assertion이 부분 성공/조용한 전체 롤백을 드러냅니다.
		assertThat(notificationCount(eventKey)).isEqualTo(size);
		assertThat(jdbc.queryForObject("""
			SELECT count(DISTINCT user_id) FROM notifications WHERE event_key = ?
			""", Long.class, eventKey)).isEqualTo(size);
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM event_notification_subscriptions WHERE event_occurrence_id = ?
			""", Long.class, occurrenceId)).isEqualTo(size);
		assertThat(observed.samples).hasSize(2);
		assertThat(observed.samples.stream().allMatch(TxSample::committed)).isTrue();
		long returns = usageAfter.returns() - usageBefore.returns();
		double usageMs = usageAfter.totalMs() - usageBefore.totalMs();
		System.out.printf(Locale.ROOT,
			"FANOUT_SAMPLE size=%d mode=%s repetition=%d tick_ms=%.3f "
				+ "hibernate_prepares=%d tx_count=%d first_tx_envelope_ms=%.3f second_tx_envelope_ms=%.3f "
				+ "pool_usage_available=%s pool_returns=%d pool_usage_total_ms=%.3f pool_usage_mean_ms=%.3f "
				+ "notifications=%d%n",
			size, mode, repetition, elapsedMs, prepares, observed.samples.size(),
			observed.samples.get(0).elapsedMs(), observed.samples.get(1).elapsedMs(),
			usageBefore.available() && usageAfter.available(), returns, usageMs,
			returns > 0 ? usageMs / returns : Double.NaN, size);
	}

	private long notificationCount(String eventKey) {
		return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_key = ?", Long.class, eventKey);
	}

	private PoolUsage poolUsage() {
		List<Timer> timers = new ArrayList<>(meterRegistry.find("hikaricp.connections.usage").timers());
		return new PoolUsage(!timers.isEmpty(), timers.stream().mapToLong(Timer::count).sum(),
			timers.stream().mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum());
	}

	private record PoolUsage(boolean available, long returns, double totalMs) {
	}

	private record TxSample(boolean committed, double elapsedMs) {
	}

	/** 앱 측 TX 개시 요청부터 commit/rollback 반환까지이며 DB BEGIN 시각을 뜻하지 않습니다. */
	private static final class ObservedTransactionManager implements PlatformTransactionManager {
		private final PlatformTransactionManager delegate;
		private final JdbcTemplate timeoutJdbc;
		private final Map<TransactionStatus, Long> starts = new IdentityHashMap<>();
		private final List<TxSample> samples = new ArrayList<>();

		private ObservedTransactionManager(PlatformTransactionManager delegate) {
			this(delegate, null);
		}

		private ObservedTransactionManager(PlatformTransactionManager delegate, JdbcTemplate timeoutJdbc) {
			this.delegate = delegate;
			this.timeoutJdbc = timeoutJdbc;
		}

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			long start = System.nanoTime();
			TransactionStatus status = delegate.getTransaction(definition);
			assertThat(status.isNewTransaction()).as("테스트 외곽 TX가 없어야 합니다").isTrue();
			starts.put(status, start);
			if (timeoutJdbc != null) {
				// CONTROLLED 시나리오만 제한한다. 순수 성능 샘플에는 추가 SQL이 없다.
				try {
					timeoutJdbc.execute("SET LOCAL statement_timeout = '30s'");
					timeoutJdbc.execute("SET LOCAL lock_timeout = '10s'");
				} catch (RuntimeException error) {
					delegate.rollback(status);
					starts.remove(status);
					throw error;
				}
			}
			return status;
		}

		@Override
		public void commit(TransactionStatus status) {
			boolean committed = false;
			try {
				delegate.commit(status);
				committed = true;
			} finally {
				finish(status, committed);
			}
		}

		@Override
		public void rollback(TransactionStatus status) {
			try {
				delegate.rollback(status);
			} finally {
				finish(status, false);
			}
		}

		private void finish(TransactionStatus status, boolean committed) {
			samples.add(new TxSample(committed, (System.nanoTime() - starts.remove(status)) / 1_000_000.0));
		}
	}
}
