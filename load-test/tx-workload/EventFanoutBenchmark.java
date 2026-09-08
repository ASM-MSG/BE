package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.msg.fillmap.event.repository.EventNotificationSubscriptionRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.notification.service.NotificationCommandService;

/**
 * 임시 로컬 감사 벤치. 별도 fillmap_tx_bench_* DB에서만 실행합니다.
 * 테스트 외곽 @Transactional 없음: 실제 scheduler의 발송/정리 두 커밋을 측정합니다.
 * 실행 전 root가 datasource를 별도 DB로 지정하고 모든 시더/AI/워커를 끕니다.
 * 출력 FANOUT_SAMPLE은 fixture 적재, 삭제, ANALYZE, 검증 SELECT 시간을 제외합니다.
 */
@SpringBootTest(properties = {
	"fillmap.notification.enabled=false",
	"fillmap.event.lifecycle.poll-interval-ms=86400000",
	"spring.jpa.properties.hibernate.generate_statistics=true",
	"logging.level.org.hibernate.stat=OFF",
	"logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class EventFanoutBenchmark {

	private static final LocalDateTime START = LocalDateTime.of(2026, 10, 6, 1, 0);
	private static final Clock CLOCK = Clock.fixed(START.plusMinutes(1).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
	private static final int[] SIZES = java.util.Arrays.stream(System.getenv()
		.getOrDefault("BENCH_FANOUT_SIZES", "1000,10000,50000").split(","))
		.mapToInt(Integer::parseInt).toArray();

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
					notificationCommandService, observed, true, CLOCK);
				String eventKey = "EVENT_START:" + tag + ":" + START.toEpochSecond(ZoneOffset.UTC);
				if (size >= 50_000) {
					// 5만 명은 로컬에서 수분이 걸리므로 단발 측정만 합니다. 반복값이 아닙니다.
					measure(scheduler, observed, stats, occurrenceId, eventKey, size, "LARGE_SINGLE", 1);
					continue;
				}
				measure(scheduler, observed, stats, occurrenceId, eventKey, size, "WARMUP_FRESH", 0);
				for (int repetition = 1; repetition <= 3; repetition++) {
					jdbc.update("DELETE FROM notifications WHERE event_key = ?", eventKey);
					assertThat(notificationCount(eventKey)).isZero();
					measure(scheduler, observed, stats, occurrenceId, eventKey, size, "FRESH", repetition);
				}
				// 이미 기록된 구독자도 production loop를 다시 통과하는 비용을 따로 1회 기록합니다.
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
		private final Map<TransactionStatus, Long> starts = new IdentityHashMap<>();
		private final List<TxSample> samples = new ArrayList<>();

		private ObservedTransactionManager(PlatformTransactionManager delegate) {
			this.delegate = delegate;
		}

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			long start = System.nanoTime();
			TransactionStatus status = delegate.getTransaction(definition);
			assertThat(status.isNewTransaction()).as("테스트 외곽 TX가 없어야 합니다").isTrue();
			starts.put(status, start);
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
