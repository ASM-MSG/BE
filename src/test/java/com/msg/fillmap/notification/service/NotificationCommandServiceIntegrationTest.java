package com.msg.fillmap.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.notification.entity.Notification;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.entity.NotificationStatus;
import com.msg.fillmap.notification.repository.NotificationRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * outbox 기록 통합 검증 (실 PostGIS, MSG-179). 호출자 트랜잭션 동반 롤백(FR-3)·ON CONFLICT
 * 멱등(FR-6)을 실제 DB 로 관찰해야 하므로 @Transactional 격리를 못 쓴다 — 합성 유저를 커밋해 두고
 * @AfterEach 에서 users 지정 삭제로 걷어낸다 (notifications 는 ON DELETE CASCADE 로 함께 걷힘 —
 * PushTokenServiceIntegrationTest 선례).
 */
@SpringBootTest
@DisplayName("NotificationCommandService outbox 기록 — 원자성·이벤트 키 멱등 (실 PostGIS)")
class NotificationCommandServiceIntegrationTest {

	@Autowired
	private NotificationCommandService notificationCommandService;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long me;
	private long other;
	private String eventKey;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		eventKey = "BADGE:" + System.nanoTime();   // 공유 로컬 DB — 실행 간 충돌 방지
		tx.executeWithoutResult(status -> {
			me = newUser("notif-me");
			other = newUser("notif-other");
		});
	}

	@AfterEach
	void tearDown() {
		// 커밋해 둔 합성 유저만 삭제 — notifications 는 ON DELETE CASCADE 로 함께 걷힌다.
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id IN (:me, :other)")
				.setParameter("me", me)
				.setParameter("other", other)
				.executeUpdate());
	}

	// 검증: FR-NOTI-02
	@Test
	@DisplayName("기록은 호출자 트랜잭션과 함께 롤백된다 — FR-3 원자성 (전파 REQUIRED 실증)")
	void 기록은_호출자_트랜잭션과_함께_롤백된다() {
		tx.executeWithoutResult(status -> {
			notificationCommandService.record(me, NotificationCategory.BADGE, eventKey, "제목", "본문");
			status.setRollbackOnly();
		});

		// REQUIRES_NEW 였다면 자체 커밋으로 행이 살아남는다 — 0행이어야 호출자 트랜잭션 참여 증명.
		assertThat(rowCount(me, eventKey)).isZero();
	}

	// 검증: FR-NOTI-03
	@Test
	@DisplayName("같은 이벤트 키 중복 기록은 한 행만 남는다 — FR-6, ON CONFLICT DO NOTHING 경로")
	void 같은_이벤트_키_중복_기록은_한_행만_남는다() {
		notificationCommandService.record(me, NotificationCategory.BADGE, eventKey, "첫 제목", "첫 본문");

		notificationCommandService.record(me, NotificationCategory.BADGE, eventKey, "재기록 제목", "재기록 본문");

		assertThat(rowCount(me, eventKey)).isEqualTo(1);
		Notification saved = notificationRepository.findById(findId(me, eventKey)).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo("첫 제목");   // DO NOTHING — 첫 기록이 이긴다
		assertThat(saved.getCategory()).isEqualTo(NotificationCategory.BADGE);
		assertThat(saved.getStatus()).isEqualTo(NotificationStatus.PENDING);   // DDL DEFAULT
		assertThat(saved.getRetryCount()).isZero();
		assertThat(saved.getCreatedAt()).isNotNull();
	}

	// 검증: FR-NOTI-03
	@Test
	@DisplayName("다른 사용자의 같은 이벤트 키는 각각 기록된다 — UNIQUE 는 (user_id, event_key) 복합")
	void 다른_사용자의_같은_이벤트_키는_각각_기록된다() {
		notificationCommandService.record(me, NotificationCategory.HOTZONE, eventKey, "제목", "본문");

		notificationCommandService.record(other, NotificationCategory.HOTZONE, eventKey, "제목", "본문");

		assertThat(rowCount(me, eventKey)).isEqualTo(1);
		assertThat(rowCount(other, eventKey)).isEqualTo(1);
	}

	@Test
	@DisplayName("markSent 는 sent_at 을 UTC 벽시계로 저장한다 — D3·D8, now() 세션 TZ 캐스트 방지")
	void markSent_는_sent_at_을_UTC_벽시계로_저장한다() {
		notificationCommandService.record(me, NotificationCategory.BADGE, eventKey, "제목", "본문");
		long id = findId(me, eventKey);

		tx.executeWithoutResult(status -> notificationRepository.markSent(id));

		Notification sent = notificationRepository.findById(id).orElseThrow();
		assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
		// now() 였다면 KST JVM 에서 +9h 로 저장돼 실패한다 — statement_timestamp() AT TIME ZONE 'UTC' 실증.
		assertThat(sent.getSentAt().toInstant(ZoneOffset.UTC))
			.isCloseTo(Instant.now(), within(10, ChronoUnit.SECONDS));
	}

	// ── 종결 전이 compare-and-set (MSG-343 Codex 4R) ──

	@Test
	@DisplayName("이미 SENT 인 행의 markSent 는 0행이다 — 동시 중복 소비의 두 번째 전이·이중 계상 차단")
	void 이미_SENT인_행의_markSent는_0행이다() {
		notificationCommandService.record(me, NotificationCategory.BADGE, eventKey, "제목", "본문");
		long id = findId(me, eventKey);
		Integer first = tx.execute(status -> notificationRepository.markSent(id));

		// 겹쳐 읽은 두 번째 소비자의 종결 시도 — 초입 가드를 통과했더라도 DB 술어가 승자 1명만 남긴다.
		Integer second = tx.execute(status -> notificationRepository.markSent(id));

		assertThat(first).isEqualTo(1);
		assertThat(second).isZero();   // 0행 → 호출부가 sent 카운터를 올리지 않는다
	}

	@Test
	@DisplayName("이미 SENT 인 행에 markDead 를 걸면 0행이고 상태가 SENT 로 유지된다 — 종결은 되덮이지 않는다")
	void 이미_SENT인_행의_markDead는_0행이고_상태가_유지된다() {
		notificationCommandService.record(me, NotificationCategory.BADGE, eventKey, "제목", "본문");
		long id = findId(me, eventKey);
		tx.executeWithoutResult(status -> notificationRepository.markSent(id));

		Integer dead = tx.execute(status -> notificationRepository.markDead(id, "FCM 다운"));

		assertThat(dead).isZero();
		Notification row = notificationRepository.findById(id).orElseThrow();
		assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
		assertThat(row.getLastError()).isNull();   // 사유도 덮이지 않는다
	}

	private long newUser(String prefix) {
		String email = prefix + "-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "알림테스터")).getId();
	}

	private long rowCount(long userId, String key) {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM notifications WHERE user_id = :userId AND event_key = :key")
			.setParameter("userId", userId)
			.setParameter("key", key)
			.getSingleResult()).longValue();
	}

	private long findId(long userId, String key) {
		return ((Number) em.createNativeQuery(
				"SELECT id FROM notifications WHERE user_id = :userId AND event_key = :key")
			.setParameter("userId", userId)
			.setParameter("key", key)
			.getSingleResult()).longValue();
	}
}
