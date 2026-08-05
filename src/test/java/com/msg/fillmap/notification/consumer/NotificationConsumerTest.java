package com.msg.fillmap.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

import com.google.firebase.messaging.FirebaseMessaging;
import com.msg.fillmap.notification.config.NotificationProperties;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.repository.PushTokenRepository;
import com.msg.fillmap.notification.sender.NotificationSender;
import com.msg.fillmap.notification.sender.NotificationSender.SendResult;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.notification.service.NotificationPreferenceService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 컨슈머 통합 검증 (MSG-179 — EmbeddedKafka + 실 PostGIS + NotificationSender 목, D12). 릴레이 주기는
 * 1h 로 밀어 두고 테스트가 행 상태·메시지 발행을 직접 제어한다. FCM 은 NotificationSender 목 —
 * FirebaseMessaging 도 목으로 대체해 자격 파일 없이 컨텍스트가 뜬다. 운영 백오프(총 ~4분)로는 재시도
 * 테스트가 못 기다리므로 같은 recoverer 를 쓰는 즉시 재시도 핸들러(@Primary)로 교체한다.
 */
@SpringBootTest(properties = {
	"fillmap.notification.enabled=true",
	"fillmap.notification.relay.poll-interval-ms=3600000"
})
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DisplayName("NotificationConsumer — 멱등·필터·발송·재시도 (EmbeddedKafka + 실 PostGIS)")
class NotificationConsumerTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Autowired
	private NotificationCommandService notificationCommandService;

	@Autowired
	private PushTokenRepository pushTokenRepository;

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private NotificationProperties properties;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	@MockitoBean
	private NotificationSender notificationSender;

	@MockitoBean
	private NotificationPreferenceService notificationPreferenceService;

	@MockitoBean
	private FirebaseMessaging firebaseMessaging;

	private TransactionTemplate tx;
	private long me;

	@TestConfiguration
	static class FastRetryErrorHandlerConfig {

		/** 운영 백오프 상수(총 ~4분) 대체 — 같은 DEAD recoverer 로 즉시 재시도(초회 + 3회 = 4시도). */
		@Bean
		@Primary
		DefaultErrorHandler fastRetryErrorHandler(ConsumerRecordRecoverer notificationDeadRecoverer) {
			DefaultErrorHandler errorHandler =
				new DefaultErrorHandler(notificationDeadRecoverer, new FixedBackOff(0L, 3L));
			// 운영 핸들러(NotificationConfig)와 동일 분류 미러 — 말폼드 즉시 폐기 경로 검증용.
			errorHandler.addNotRetryableExceptions(NumberFormatException.class);
			return errorHandler;
		}
	}

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> me = userRepository.save(
			User.createLocalUser("consumer-" + System.nanoTime() + "@example.com", "hash", "컨슈머테스터")).getId());
		given(notificationPreferenceService.isEnabled(anyLong(), any())).willReturn(true);
		given(notificationSender.send(anyList(), anyString(), anyString()))
			.willReturn(new SendResult(1, List.of()));
	}

	@AfterEach
	void tearDown() {
		// push_tokens·notifications 는 users ON DELETE CASCADE 로 함께 걷힌다.
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :me").setParameter("me", me).executeUpdate());
	}

	@Test
	@DisplayName("PUBLISHED 행을 발송하고 SENT 와 sent_at 을 기록한다")
	void PUBLISHED_행을_발송하고_SENT와_sent_at을_기록한다() throws Exception {
		String token = registerToken("ok-" + System.nanoTime());
		long id = newNotification(NotificationCategory.BADGE, "발송제목");

		publish(id);

		awaitStatus(id, "SENT");
		assertThat(sentAtOf(id)).isNotNull();
		then(notificationSender).should().send(eq(List.of(token)), eq("발송제목"), eq("본문"));
	}

	@Test
	@DisplayName("이미 SENT 인 행 재소비는 발송하지 않는다 — FR-6 2차 방어 (파티션 1 순서 보장으로 검증)")
	void 이미_SENT인_행_재소비는_발송하지_않는다() throws Exception {
		registerToken("ok-" + System.nanoTime());
		long already = newNotification(NotificationCategory.BADGE, "A제목");
		setStatus(already, "SENT");
		long fresh = newNotification(NotificationCategory.BADGE, "B제목");

		sendMessage(already);   // 재전달 시뮬레이션 — 종결 행의 재소비
		publish(fresh);

		awaitStatus(fresh, "SENT");   // 같은 파티션 — fresh 처리 완료 = already 소비도 끝났다
		assertThat(statusOf(already)).isEqualTo("SENT");
		assertThat(retryCountOf(already)).isZero();   // 종결 판정이 retry_count 증가보다 앞선다
		then(notificationSender).should(never()).send(anyList(), eq("A제목"), anyString());
	}

	@Test
	@DisplayName("설정 off 사용자는 SKIPPED PREF_OFF 로 기록된다 — FR-8 접점 (목 preference)")
	void 설정_off_사용자는_SKIPPED_PREF_OFF로_기록된다() throws Exception {
		given(notificationPreferenceService.isEnabled(eq(me), eq(NotificationCategory.BADGE))).willReturn(false);
		long id = newNotification(NotificationCategory.BADGE, "제목");

		publish(id);

		awaitStatus(id, "SKIPPED");
		assertThat(lastErrorOf(id)).isEqualTo("PREF_OFF");
		then(notificationSender).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("HOTZONE 일 상한 초과는 SKIPPED RATE_LIMITED 다 — 같은 날 SENT 1건 선재 (FR-12)")
	void HOTZONE_일_상한_초과는_SKIPPED_RATE_LIMITED다() throws Exception {
		long sentToday = newNotification(NotificationCategory.HOTZONE, "선재");
		setSent(sentToday, LocalDateTime.now(ZoneOffset.UTC));
		long id = newNotification(NotificationCategory.HOTZONE, "초과분");

		publish(id);

		awaitStatus(id, "SKIPPED");
		assertThat(lastErrorOf(id)).isEqualTo("RATE_LIMITED");
	}

	@Test
	@DisplayName("날이 바뀌면 HOTZONE 이 다시 발송된다 — KST 자정 경계 (sent_at 조작)")
	void 날이_바뀌면_HOTZONE이_다시_발송된다() throws Exception {
		LocalDateTime kstMidnightUtc = LocalDate.now(KST).atStartOfDay(KST)
			.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
		long sentYesterday = newNotification(NotificationCategory.HOTZONE, "어제");
		setSent(sentYesterday, kstMidnightUtc.minusHours(1));   // KST 어제 — 오늘 카운트에 안 잡힌다
		registerToken("ok-" + System.nanoTime());
		long id = newNotification(NotificationCategory.HOTZONE, "오늘");

		publish(id);

		awaitStatus(id, "SENT");
	}

	@Test
	@DisplayName("BADGE 는 상한 없이 연속 발송된다 — FR-12, 설정 키 자체가 없다")
	void BADGE는_상한_없이_연속_발송된다() throws Exception {
		registerToken("ok-" + System.nanoTime());
		long first = newNotification(NotificationCategory.BADGE, "첫째");
		long second = newNotification(NotificationCategory.BADGE, "둘째");

		publish(first);
		publish(second);

		awaitStatus(first, "SENT");
		awaitStatus(second, "SENT");
	}

	@Test
	@DisplayName("VIDEO 는 전송률 제한 없이 발송된다 — MSG-313 FR-9, 설정 키 자체가 없다")
	void VIDEO는_전송률_제한_없이_발송된다() throws Exception {
		registerToken("ok-" + System.nanoTime());
		long first = newNotification(NotificationCategory.VIDEO, "첫째");
		long second = newNotification(NotificationCategory.VIDEO, "둘째");

		publish(first);
		publish(second);

		awaitStatus(first, "SENT");
		awaitStatus(second, "SENT");
	}

	@Test
	@DisplayName("WEEKLY 는 전송률 제한 없이 발송된다 — MSG-315 D6, 이벤트 키가 주 단위라 상한이 불필요")
	void WEEKLY는_전송률_제한_없이_발송된다() throws Exception {
		registerToken("ok-" + System.nanoTime());
		long first = newNotification(NotificationCategory.WEEKLY, "첫째");
		long second = newNotification(NotificationCategory.WEEKLY, "둘째");

		publish(first);
		publish(second);

		awaitStatus(first, "SENT");
		awaitStatus(second, "SENT");
	}

	@Test
	@DisplayName("토큰이 없으면 SKIPPED NO_TOKEN 이다")
	void 토큰이_없으면_SKIPPED_NO_TOKEN이다() throws Exception {
		long id = newNotification(NotificationCategory.BADGE, "제목");

		publish(id);

		awaitStatus(id, "SKIPPED");
		assertThat(lastErrorOf(id)).isEqualTo("NO_TOKEN");
		then(notificationSender).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("UNREGISTERED 토큰은 push_tokens 에서 삭제된다 — FR-5, 소유 검증 없는 시스템 삭제 경로")
	void UNREGISTERED_토큰은_push_tokens에서_삭제된다() throws Exception {
		String alive = registerToken("ok-" + System.nanoTime());
		String dead = registerToken("dead-" + System.nanoTime());
		given(notificationSender.send(anyList(), anyString(), anyString()))
			.willReturn(new SendResult(1, List.of(dead)));
		long id = newNotification(NotificationCategory.BADGE, "제목");

		publish(id);

		awaitStatus(id, "SENT");
		assertThat(tokenCount(dead)).isZero();
		assertThat(tokenCount(alive)).isEqualTo(1);
	}

	@Test
	@DisplayName("일부 토큰만 성공해도 SENT 다 — D5 부분 실패 정책 (토큰별 상태 추적 없음)")
	void 일부_토큰만_성공해도_SENT다() throws Exception {
		String first = registerToken("ok1-" + System.nanoTime());
		String second = registerToken("ok2-" + System.nanoTime());
		given(notificationSender.send(anyList(), anyString(), anyString()))
			.willReturn(new SendResult(1, List.of()));   // 2개 중 1개만 성공, 무효 아님(순단)
		long id = newNotification(NotificationCategory.BADGE, "제목");

		publish(id);

		awaitStatus(id, "SENT");
		assertThat(tokenCount(first)).isEqualTo(1);
		assertThat(tokenCount(second)).isEqualTo(1);
	}

	@Test
	@DisplayName("전부 실패가 상한을 넘으면 DEAD 로 격리되고 last_error 가 남는다 — FR-4")
	void 전부_실패가_상한을_넘으면_DEAD로_격리되고_last_error가_남는다() throws Exception {
		registerToken("ok-" + System.nanoTime());
		given(notificationSender.send(anyList(), anyString(), anyString()))
			.willThrow(new IllegalStateException("FCM 다운"));
		long id = newNotification(NotificationCategory.BADGE, "제목");

		publish(id);

		awaitStatus(id, "DEAD");
		assertThat(lastErrorOf(id)).contains("FCM 다운");
		assertThat(retryCountOf(id)).isEqualTo(4);   // 초회 + 재시도 3회 (테스트 핸들러 상한)
	}

	@Test
	@DisplayName("말폼드 레코드는 재시도 없이 폐기되고 후속 정상 레코드 처리가 계속된다 — NumberFormatException 비재시도 분류")
	void 말폼드_레코드는_재시도_없이_폐기되고_후속_정상_레코드_처리가_계속된다() throws Exception {
		registerToken("ok-" + System.nanoTime());
		kafkaTemplate.send(properties.topic(), "not-a-number").get();   // 말폼드 — outbox id 가 아닌 페이로드
		long id = newNotification(NotificationCategory.BADGE, "정상");

		publish(id);

		// 파티션 1 순서 보장 — 말폼드가 파티션을 막았다면(재시도 소진 대기) 후속 행이 SENT 에 못 이른다.
		awaitStatus(id, "SENT");
	}

	@Test
	@DisplayName("재시도 횟수가 retry_count 에 남는다 — 몇 번 만에 성공했는지 (D4)")
	void 재시도_횟수가_retry_count에_남는다() throws Exception {
		registerToken("ok-" + System.nanoTime());
		given(notificationSender.send(anyList(), anyString(), anyString()))
			.willThrow(new IllegalStateException("1차 실패"))
			.willThrow(new IllegalStateException("2차 실패"))
			.willReturn(new SendResult(1, List.of()));
		long id = newNotification(NotificationCategory.BADGE, "제목");

		publish(id);

		awaitStatus(id, "SENT");
		assertThat(retryCountOf(id)).isEqualTo(3);   // 실패 2회 뒤 3번째 시도에 성공
	}

	private long newNotification(NotificationCategory category, String title) {
		String eventKey = category.name() + ":" + System.nanoTime();
		notificationCommandService.record(me, category, eventKey, title, "본문");
		return ((Number) em.createNativeQuery(
				"SELECT id FROM notifications WHERE user_id = :userId AND event_key = :key")
			.setParameter("userId", me)
			.setParameter("key", eventKey)
			.getSingleResult()).longValue();
	}

	private String registerToken(String token) {
		tx.executeWithoutResult(status -> pushTokenRepository.upsert(token, me, "WEB", null));
		return token;
	}

	/** PUBLISHED 전이 + 메시지 발행 — 릴레이가 하는 일을 테스트가 직접 수행해 시점을 제어한다. */
	private void publish(long id) throws Exception {
		setStatus(id, "PUBLISHED");
		sendMessage(id);
	}

	private void sendMessage(long id) throws Exception {
		kafkaTemplate.send(properties.topic(), String.valueOf(id)).get();
	}

	private void setStatus(long id, String status) {
		tx.executeWithoutResult(s ->
			em.createNativeQuery("UPDATE notifications SET status = :status WHERE id = :id")
				.setParameter("status", status)
				.setParameter("id", id)
				.executeUpdate());
	}

	private void setSent(long id, LocalDateTime sentAt) {
		tx.executeWithoutResult(s ->
			em.createNativeQuery("UPDATE notifications SET status = 'SENT', sent_at = :sentAt WHERE id = :id")
				.setParameter("sentAt", sentAt)
				.setParameter("id", id)
				.executeUpdate());
	}

	private void awaitStatus(long id, String expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < deadline && !expected.equals(statusOf(id))) {
			Thread.sleep(100);
		}
		assertThat(statusOf(id)).isEqualTo(expected);
	}

	private String statusOf(long id) {
		return (String) em.createNativeQuery("SELECT status FROM notifications WHERE id = :id")
			.setParameter("id", id)
			.getSingleResult();
	}

	private String lastErrorOf(long id) {
		return (String) em.createNativeQuery("SELECT last_error FROM notifications WHERE id = :id")
			.setParameter("id", id)
			.getSingleResult();
	}

	private int retryCountOf(long id) {
		return ((Number) em.createNativeQuery("SELECT retry_count FROM notifications WHERE id = :id")
			.setParameter("id", id)
			.getSingleResult()).intValue();
	}

	private Object sentAtOf(long id) {
		return em.createNativeQuery("SELECT sent_at FROM notifications WHERE id = :id")
			.setParameter("id", id)
			.getSingleResult();
	}

	private long tokenCount(String token) {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM push_tokens WHERE fcm_token = :token")
			.setParameter("token", token)
			.getSingleResult()).longValue();
	}
}
