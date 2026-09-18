package com.msg.fillmap.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.notification.config.NotificationProperties;
import com.msg.fillmap.notification.consumer.NotificationConsumer;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.relay.NotificationRelay;
import com.msg.fillmap.notification.repository.NotificationRepository;
import com.msg.fillmap.notification.repository.PushTokenRepository;
import com.msg.fillmap.notification.sender.NotificationSender.SendResult;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.notification.service.NotificationPreferenceService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/** 실제 DB 오류로 외부 발송과 DB 종결 사이의 원자성 한계를 재현합니다. 실제 FCM은 호출하지 않습니다. */
@SpringBootTest(properties = "fillmap.notification.enabled=false")
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class NotificationCrashWindowTest {

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private NotificationRepository repository;
	@Autowired
	private PushTokenRepository tokens;
	@Autowired
	private NotificationPreferenceService preferences;
	@Autowired
	private NotificationCommandService commands;
	@Autowired
	private NotificationProperties properties;
	@Autowired
	private PlatformTransactionManager manager;
	@Autowired
	private UserRepository users;
	@Autowired
	private org.springframework.kafka.test.EmbeddedKafkaBroker broker;
	private KafkaTemplate<String, String> kafka;
	private org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String> producerFactory;
	@Autowired
	private ScheduledAnnotationBeanPostProcessor schedules;
	private boolean safeDatabase;
	private long userId;
	private long id;
	private final AtomicInteger deliveries = new AtomicInteger();
	private final List<Long> deliveredIds = new java.util.ArrayList<>();

	@BeforeEach
	void prepare() {
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
			.isEqualTo("fillmap_tx_bench_20260917");
		safeDatabase = true;
		schedules.getScheduledTasks().forEach(task -> task.cancel(false));
		producerFactory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(java.util.Map.of(
			"bootstrap.servers", broker.getBrokersAsString(),
			"key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
			"value.serializer", "org.apache.kafka.common.serialization.StringSerializer"));
		kafka = new KafkaTemplate<>(producerFactory);
		new TransactionTemplate(manager).executeWithoutResult(tx -> {
			userId = users.save(User.createLocalUser(UUID.randomUUID() + "@example.invalid", "hash", "시험")).getId();
			tokens.upsert("fake-" + userId, userId, "WEB", null);
		});
		commands.record(userId, NotificationCategory.VIDEO, "FAULT:" + userId, "title", "body");
		id = jdbc.queryForObject("SELECT id FROM notifications WHERE user_id=?", Long.class, userId);
	}

	@AfterEach
	void clean() {
		if (!safeDatabase) {
			return;
		}
		if (producerFactory != null) {
			producerFactory.destroy();
		}
		jdbc.execute("DROP TRIGGER IF EXISTS bench_notification_fault ON notifications");
		jdbc.execute("DROP FUNCTION IF EXISTS bench_notification_fault()");
		jdbc.execute("DROP SEQUENCE IF EXISTS bench_notification_fault_seq");
		jdbc.update("DELETE FROM users WHERE id=?", userId);
	}

	private void failFirstTransition(String target) {
		assertThat(target).isIn("SENT", "PUBLISHED");
		jdbc.execute("CREATE SEQUENCE bench_notification_fault_seq");
		// nextval은 롤백되지 않습니다. 정확히 첫 시도에만 실제 DB 오류를 주입합니다.
		jdbc.execute("""
			CREATE FUNCTION bench_notification_fault() RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
			  IF NEW.id = %d AND NEW.status = '%s' THEN
			    IF nextval('bench_notification_fault_seq') = 1 THEN
			      RAISE EXCEPTION 'injected after external success';
			    END IF;
			  END IF;
			  RETURN NEW;
			END $$
			""".formatted(id, target));
		jdbc.execute("CREATE TRIGGER bench_notification_fault BEFORE UPDATE ON notifications "
			+ "FOR EACH ROW EXECUTE FUNCTION bench_notification_fault()");
	}

	private NotificationConsumer consumer() {
		return new NotificationConsumer(repository, tokens, preferences, (notificationId, receivers, title, body) -> {
			deliveredIds.add(notificationId);
			deliveries.incrementAndGet();
			return new SendResult(1, List.of());
		}, properties, manager, new SimpleMeterRegistry());
	}

	@Test
	void 발송_성공_뒤_DB_실패는_재전달에서_외부_발송이_중복된다() {
		failFirstTransition("SENT");
		NotificationConsumer consumer = consumer();
		assertThatThrownBy(() -> consumer.consume(Long.toString(id))).isInstanceOf(RuntimeException.class);
		assertThat(status()).isEqualTo("PENDING");
		assertThat(deliveries.get()).isEqualTo(1);
		consumer.consume(Long.toString(id));
		assertThat(status()).isEqualTo("SENT");
		assertThat(deliveries.get()).isEqualTo(2);
		assertThat(deliveredIds).containsExactly(id, id);
		consumer.consume(Long.toString(id));
		assertThat(deliveries.get()).isEqualTo(2);
		System.out.println("FAULT_RESULT boundary=send_then_db deliveries=2 rows=1 final=SENT terminal_replay=0");
	}

	@Test
	void Kafka_발행_뒤_DB_실패는_재발행되고_종결_후_중복은_걸러진다() throws Exception {
		failFirstTransition("PUBLISHED");
		NotificationRelay relay = new NotificationRelay(repository, kafka, properties, manager);
		relay.relay();
		assertThat(status()).isEqualTo("PENDING");
		relay.relay();
		assertThat(status()).isEqualTo("PUBLISHED");
		// 실제 브로커에 기록된 두 레코드를 새 그룹으로 읽어 재발행 여부를 확인합니다.
		try (var reader = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(java.util.Map.of(
			"bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"),
			"group.id", "fault-" + UUID.randomUUID(), "auto.offset.reset", "earliest",
			"key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
			"value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"))) {
			reader.subscribe(List.of(properties.topic()));
			int records = 0;
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
			NotificationConsumer consumer = consumer();
			while (records < 2 && System.nanoTime() < deadline) {
				for (var record : reader.poll(java.time.Duration.ofMillis(200))) {
					if (record.value().equals(Long.toString(id))) {
						records++;
						consumer.consume(record.value());
					}
				}
			}
			assertThat(records).isEqualTo(2);
			assertThat(deliveries.get()).isEqualTo(1);
			assertThat(status()).isEqualTo("SENT");
			System.out.println("FAULT_RESULT boundary=kafka_then_db records=2 deliveries=1 final=SENT");
		}
	}

	@Test
	void 브로커_접속_실패의_100건은_PENDING으로_남고_복구_후_종결된다() throws Exception {
		for (int i = 1; i < 100; i++) {
			commands.record(userId, NotificationCategory.VIDEO, "OUTAGE:" + userId + ":" + i, "title", "body");
		}
		var unreachableFactory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(
			java.util.Map.of("bootstrap.servers", "127.0.0.1:1", "max.block.ms", 1000,
				"request.timeout.ms", 1000, "delivery.timeout.ms", 1500,
				"key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
				"value.serializer", "org.apache.kafka.common.serialization.StringSerializer"));
		try {
			new NotificationRelay(repository, new KafkaTemplate<>(unreachableFactory), properties, manager).relay();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND status='PENDING'",
				Long.class, userId)).isEqualTo(100);
		} finally {
			unreachableFactory.destroy();
		}
		long start = System.nanoTime();
		new NotificationRelay(repository, kafka, properties, manager).relay();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND status='PUBLISHED'",
			Long.class, userId)).isEqualTo(100);
		NotificationConsumer consumer = consumer();
		var ids = jdbc.queryForList("SELECT id FROM notifications WHERE user_id=? ORDER BY id", Long.class, userId);
		try (var reader = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(java.util.Map.of(
			"bootstrap.servers", broker.getBrokersAsString(),
			"group.id", "outage-" + UUID.randomUUID(), "auto.offset.reset", "earliest",
			"key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
			"value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"))) {
			reader.subscribe(List.of(properties.topic()));
			var observed = new java.util.HashSet<Long>();
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
			while (observed.size() < 100 && System.nanoTime() < deadline) {
				for (var record : reader.poll(java.time.Duration.ofMillis(200))) {
					long item = Long.parseLong(record.value());
					if (ids.contains(item)) {
						assertThat(observed.add(item)).isTrue();
						consumer.consume(record.value());
						consumer.consume(record.value());
					}
				}
			}
			assertThat(observed).hasSize(100);
		}
		assertThat(deliveries.get()).isEqualTo(100);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND status='SENT'",
			Long.class, userId)).isEqualTo(100);
		System.out.printf(java.util.Locale.ROOT,
			"FAULT_RESULT boundary=broker_unreachable pending=100 recovered=100 deliveries=100 recovery_ms=%.3f%n",
			(System.nanoTime() - start) / 1_000_000.0);
	}

	private String status() {
		return jdbc.queryForObject("SELECT status FROM notifications WHERE id=?", String.class, id);
	}
}
