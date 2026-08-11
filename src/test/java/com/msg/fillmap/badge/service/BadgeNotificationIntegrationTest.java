package com.msg.fillmap.badge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 뱃지 발급 → BADGE 알림 배선 통합 검증 (실 PostGIS, MSG-181 D1·D2). 발급과 같은 커밋에 남는
 * 원자성(FR-3)·롤백 동반 소멸을 실제 DB 로 관찰해야 하므로 @Transactional 격리를 못 쓴다 —
 * 합성 유저를 커밋해 두고 @AfterEach 에서 users 지정 삭제로 걷어낸다 (user_badges·notifications
 * 모두 ON DELETE CASCADE — NotificationCommandServiceIntegrationTest 선례).
 */
@SpringBootTest
@DisplayName("BadgeAwardService → BADGE 알림 배선 (실 PostGIS)")
class BadgeNotificationIntegrationTest {

	@Autowired
	private BadgeAwardService badgeAwardService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long userId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		String email = "badge-notif-" + System.nanoTime() + "@example.com";
		tx.executeWithoutResult(status ->
			userId = userRepository.save(User.createLocalUser(email, "hash", "뱃지알림테스터")).getId());
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :userId")
				.setParameter("userId", userId)
				.executeUpdate());
	}

	// 검증: FR-NOTI-08
	@Test
	@DisplayName("뱃지 발급 시 뱃지당 BADGE 알림이 한 건씩 기록된다 — 다중 발급 N건, event_key·문구 (D1·D2)")
	void 뱃지_발급_시_뱃지당_BADGE_알림이_한_건씩_기록된다() {
		// TOTAL_GRIDS 12 는 EXPLORER_1(1)·EXPLORER_10(10) 두 티어를 동시 통과한다 — 뱃지당 1건씩.
		badgeAwardService.award(userId, BadgeConditionType.TOTAL_GRIDS, BigDecimal.valueOf(12));

		assertThat(notificationCount()).isEqualTo(2);
		Object[] explorer1 = notificationRow("BADGE:" + badgeId("EXPLORER_1"));
		assertThat(explorer1[0]).isEqualTo("BADGE");
		assertThat(explorer1[1]).isEqualTo("새 뱃지 획득");
		assertThat(explorer1[2]).isEqualTo("'첫 발자국' 뱃지를 획득했어요");
		Object[] explorer10 = notificationRow("BADGE:" + badgeId("EXPLORER_10"));
		assertThat(explorer10[2]).isEqualTo("'탐험가 I' 뱃지를 획득했어요");
	}

	// 검증: FR-NOTI-08
	@Test
	@DisplayName("이미 보유한 뱃지 재판정은 알림을 기록하지 않는다 — award 0건 경로 (D1)")
	void 이미_보유한_뱃지_재판정은_알림을_기록하지_않는다() {
		badgeAwardService.award(userId, BadgeConditionType.TOTAL_GRIDS, BigDecimal.ONE);
		assertThat(notificationCount()).isEqualTo(1);

		badgeAwardService.award(userId, BadgeConditionType.TOTAL_GRIDS, BigDecimal.ONE);

		assertThat(notificationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("발급 트랜잭션이 롤백되면 알림도 남지 않는다 — FR-3 원자성 (전파 REQUIRED 실증)")
	void 발급_트랜잭션이_롤백되면_알림도_남지_않는다() {
		tx.executeWithoutResult(status -> {
			badgeAwardService.award(userId, BadgeConditionType.TOTAL_GRIDS, BigDecimal.ONE);
			status.setRollbackOnly();
		});

		// REQUIRES_NEW 였다면 자체 커밋으로 알림만 살아남는다 — 발급·알림 모두 0행이어야 같은 커밋 증명.
		assertThat(earnedCount()).isZero();
		assertThat(notificationCount()).isZero();
	}

	private long notificationCount() {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM notifications WHERE user_id = :userId")
			.setParameter("userId", userId)
			.getSingleResult()).longValue();
	}

	/** category·title·body 스냅샷 — 단언은 호출부에서. */
	private Object[] notificationRow(String eventKey) {
		return (Object[]) em.createNativeQuery("""
				SELECT category, title, body FROM notifications
				WHERE user_id = :userId AND event_key = :eventKey
				""")
			.setParameter("userId", userId)
			.setParameter("eventKey", eventKey)
			.getSingleResult();
	}

	private long earnedCount() {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM user_badges WHERE user_id = :userId")
			.setParameter("userId", userId)
			.getSingleResult()).longValue();
	}

	private long badgeId(String code) {
		return ((Number) em.createNativeQuery("SELECT id FROM badges WHERE code = :code")
			.setParameter("code", code)
			.getSingleResult()).longValue();
	}
}
