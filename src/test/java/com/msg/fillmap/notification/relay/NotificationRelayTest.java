package com.msg.fillmap.notification.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
			// 중단된 이전 실행이 남긴 이 클래스 소유 행(RELAY: 키 네임스페이스)만 선정리 — 배치(오래된 순)
			// 선점 방지. 전역 삭제는 금지: 공유 로컬 DB 의 타 사용자·타 브랜치 행을 지우면 안 된다
			// (기존 통합 테스트들의 "자기 소유만 정리" 관례 — NotificationCommandServiceIntegrationTest 등).
			em.createNativeQuery("DELETE FROM notifications WHERE event_key LIKE 'RELAY:%'").executeUpdate();
			me = userRepository.save(
				User.createLocalUser("relay-" + System.nanoTime() + "@example.com", "hash", "릴레이테스터")).getId();
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :me").setParameter("me", me).executeUpdate());
	}

	// 검증: FR-NOTI-02
	@Test
	@DisplayName("PENDING 행을 발행하고 PUBLISHED 로 갱신한다 — 발행은 별도 그룹 재소비로 실증")
	void PENDING_행을_발행하고_PUBLISHED로_갱신한다() {
		long id = newNotification();

		notificationRelay.relay();

		assertThat(statusOf(id)).isEqualTo("PUBLISHED");
		assertThat(topicContains(String.valueOf(id))).isTrue();
	}

	// 검증: FR-NOTI-02
	@Test
	@DisplayName("발행 실패 시 상태가 유지돼 다음 주기에 재시도된다 — 브로커 오류 주입 시 배치 중단, PENDING 잔존")
	@SuppressWarnings("unchecked")
	void 발행_실패_시_상태가_유지돼_다음_주기에_재시도된다() {
		long first = newNotification();
		long second = newNotification();
		KafkaTemplate<String, String> brokenTemplate = mock(KafkaTemplate.class);
		given(brokenTemplate.send(anyString(), anyString()))
			.willReturn(CompletableFuture.failedFuture(new KafkaException("브로커 다운")));
		NotificationRelay brokenRelay =
			new NotificationRelay(notificationRepository, brokenTemplate, properties, txManager);

		brokenRelay.relay();

		assertThat(statusOf(first)).isEqualTo("PENDING");
		// 실패 지점 이후 배치 중단 — 브로커 다운 시 행별 delivery timeout 대기로 스케줄링 스레드가 잠기지 않는다.
		assertThat(statusOf(second)).isEqualTo("PENDING");
		then(brokenTemplate).should(times(1)).send(anyString(), anyString());

		notificationRelay.relay();   // 다음 주기(정상 브로커) — 같은 행들이 그대로 재발행된다

		assertThat(statusOf(first)).isEqualTo("PUBLISHED");
		assertThat(statusOf(second)).isEqualTo("PUBLISHED");
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

	// 검증: FR-NOTI-02
	@Test
	@DisplayName("오래된 PUBLISHED 행은 PENDING 으로 복구돼 재발행된다 — 컨슈머 장기 다운 대비, 최근 발행 행은 리셋 안 됨")
	@SuppressWarnings("unchecked")
	void 오래된_PUBLISHED_행은_PENDING으로_복구돼_재발행된다() {
		long stale = newNotification();
		setStatus(stale, "PUBLISHED");
		setPublishedAt(stale, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(31));   // 발행 후 threshold(30분) 초과
		long fresh = newNotification();
		setStatus(fresh, "PUBLISHED");
		setPublishedAt(fresh, LocalDateTime.now(ZoneOffset.UTC));                    // 방금 발행
		// 발행 기록용 목 템플릿 — 실발행 경로는 위 발행 테스트가 이미 검증, 여기선 "누가 재발행됐나"만 본다.
		KafkaTemplate<String, String> recordingTemplate = mock(KafkaTemplate.class);
		given(recordingTemplate.send(anyString(), anyString()))
			.willReturn(CompletableFuture.completedFuture(null));
		NotificationRelay relay =
			new NotificationRelay(notificationRepository, recordingTemplate, properties, txManager);

		relay.relay();

		// 리셋 → 같은 사이클의 findPendingBatch 가 재발행 → 다시 PUBLISHED 로 도달.
		assertThat(statusOf(stale)).isEqualTo("PUBLISHED");
		then(recordingTemplate).should().send(properties.topic(), String.valueOf(stale));
		assertThat(statusOf(fresh)).isEqualTo("PUBLISHED");
		then(recordingTemplate).should(never()).send(properties.topic(), String.valueOf(fresh));
	}

	@Test
	@DisplayName("백로그 행은 발행 직후 리셋되지 않는다 — created_at 기준이면 5s 재발행 폭주 (Codex 4R P1 회귀 방어)")
	@SuppressWarnings("unchecked")
	void 백로그_행은_발행_직후_리셋되지_않는다() {
		long backlog = newNotification();
		setCreatedAt(backlog, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(31));   // 기록 30분 경과 후 첫 발행된 백로그
		setStatus(backlog, "PUBLISHED");
		setPublishedAt(backlog, LocalDateTime.now(ZoneOffset.UTC));                  // 방금 발행됨
		KafkaTemplate<String, String> recordingTemplate = mock(KafkaTemplate.class);
		NotificationRelay relay =
			new NotificationRelay(notificationRepository, recordingTemplate, properties, txManager);

		relay.relay();

		// created_at 기준 구현이면 리셋 → 재발행(send 발생)으로 실패한다 — 판정은 발행 시각 기준이어야 한다.
		assertThat(statusOf(backlog)).isEqualTo("PUBLISHED");
		then(recordingTemplate).should(never()).send(anyString(), anyString());
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

	private void setStatus(long id, String status) {
		tx.executeWithoutResult(s ->
			em.createNativeQuery("UPDATE notifications SET status = :status WHERE id = :id")
				.setParameter("status", status)
				.setParameter("id", id)
				.executeUpdate());
	}

	private void setCreatedAt(long id, LocalDateTime createdAt) {
		tx.executeWithoutResult(s ->
			em.createNativeQuery("UPDATE notifications SET created_at = :createdAt WHERE id = :id")
				.setParameter("createdAt", createdAt)
				.setParameter("id", id)
				.executeUpdate());
	}

	private void setPublishedAt(long id, LocalDateTime publishedAt) {
		tx.executeWithoutResult(s ->
			em.createNativeQuery("UPDATE notifications SET published_at = :publishedAt WHERE id = :id")
				.setParameter("publishedAt", publishedAt)
				.setParameter("id", id)
				.executeUpdate());
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
