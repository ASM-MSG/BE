package com.msg.fillmap.notification.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.persistence.EntityManager;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.firebase.messaging.FirebaseMessaging;
import com.msg.fillmap.notification.config.NotificationProperties;
import com.msg.fillmap.notification.consumer.NotificationConsumer;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.repository.NotificationRepository;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 릴레이 통합 검증 (MSG-179 D13 — EmbeddedKafka + 실 PostGIS). poll-interval 1h — @Scheduled 기동 1회
 * 이후엔 테스트가 relay() 를 직접 호출해 백그라운드 주기의 개입을 차단한다. 발행 실증은 별도 그룹의
 * 검증 컨슈머로 토픽을 재소비해 확인한다. FirebaseMessaging(자격 파일 필요)과 NotificationConsumer
 * (발행 직후 상태를 종결로 밀어 PUBLISHED 단언을 흔든다)는 목으로 대체.
 */
@SpringBootTest(properties = {
	"fillmap.notification.enabled=true",
	"fillmap.notification.relay.poll-interval-ms=3600000",
	"fillmap.notification.relay.batch-size=2"
})
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DisplayName("NotificationRelay — PENDING 발행·PUBLISHED 전이 (EmbeddedKafka + 실 PostGIS)")
class NotificationRelayTest {

	@Autowired
	private NotificationRelay notificationRelay;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private NotificationCommandService notificationCommandService;

	@Autowired
	private NotificationProperties properties;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	@MockitoBean
	private FirebaseMessaging firebaseMessaging;

	@MockitoBean
	private NotificationConsumer notificationConsumer;

	private TransactionTemplate tx;
	private long me;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			// 공유 로컬 DB 의 잔존 PENDING 이 배치(오래된 순)를 선점하면 단언이 흔들린다 — 선정리.
			em.createNativeQuery("DELETE FROM notifications WHERE status = 'PENDING'").executeUpdate();
			me = userRepository.save(
				User.createLocalUser("relay-" + System.nanoTime() + "@example.com", "hash", "릴레이테스터")).getId();
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :me").setParameter("me", me).executeUpdate());
	}

	@Test
	@DisplayName("PENDING 행을 발행하고 PUBLISHED 로 갱신한다 — 발행은 별도 그룹 재소비로 실증")
	void PENDING_행을_발행하고_PUBLISHED로_갱신한다() {
		long id = newNotification();

		notificationRelay.relay();

		assertThat(statusOf(id)).isEqualTo("PUBLISHED");
		assertThat(topicContains(String.valueOf(id))).isTrue();
	}

	@Test
	@DisplayName("발행 실패 시 상태가 유지돼 다음 주기에 재시도된다 — 브로커 오류 주입, PENDING 잔존")
	@SuppressWarnings("unchecked")
	void 발행_실패_시_상태가_유지돼_다음_주기에_재시도된다() {
		long id = newNotification();
		KafkaTemplate<String, String> brokenTemplate = mock(KafkaTemplate.class);
		given(brokenTemplate.send(anyString(), anyString()))
			.willReturn(CompletableFuture.failedFuture(new KafkaException("브로커 다운")));
		NotificationRelay brokenRelay =
			new NotificationRelay(notificationRepository, brokenTemplate, properties, txManager);

		brokenRelay.relay();

		assertThat(statusOf(id)).isEqualTo("PENDING");

		notificationRelay.relay();   // 다음 주기(정상 브로커) — 같은 행이 그대로 재발행된다

		assertThat(statusOf(id)).isEqualTo("PUBLISHED");
	}

	@Test
	@DisplayName("배치 크기만큼만 오래된 순으로 가져온다 — batch-size=2, 최신 행은 다음 주기로")
	void 배치_크기만큼만_오래된_순으로_가져온다() {
		long first = newNotification();
		long second = newNotification();
		long third = newNotification();

		notificationRelay.relay();

		assertThat(statusOf(first)).isEqualTo("PUBLISHED");
		assertThat(statusOf(second)).isEqualTo("PUBLISHED");
		assertThat(statusOf(third)).isEqualTo("PENDING");
	}

	private long newNotification() {
		String eventKey = "RELAY:" + System.nanoTime();
		notificationCommandService.record(me, NotificationCategory.BADGE, eventKey, "제목", "본문");
		return ((Number) em.createNativeQuery(
				"SELECT id FROM notifications WHERE user_id = :userId AND event_key = :key")
			.setParameter("userId", me)
			.setParameter("key", eventKey)
			.getSingleResult()).longValue();
	}

	private String statusOf(long id) {
		return (String) em.createNativeQuery("SELECT status FROM notifications WHERE id = :id")
			.setParameter("id", id)
			.getSingleResult();
	}

	/** 별도 그룹 + earliest 로 토픽을 처음부터 재소비해 발행 사실을 확인한다 (10초 상한). */
	private boolean topicContains(String expectedValue) {
		Map<String, Object> props = Map.of(
			ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
			ConsumerConfig.GROUP_ID_CONFIG, "relay-verify-" + System.nanoTime(),
			ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
			ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
			ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
			consumer.subscribe(List.of(properties.topic()));
			long deadline = System.currentTimeMillis() + 10_000;
			while (System.currentTimeMillis() < deadline) {
				for (ConsumerRecord<String, String> consumerRecord : consumer.poll(Duration.ofMillis(500))) {
					if (consumerRecord.value().equals(expectedValue)) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
