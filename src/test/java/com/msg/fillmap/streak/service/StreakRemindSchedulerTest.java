package com.msg.fillmap.streak.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.streak.repository.StreakRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 스트릭 리마인드 배치 검증 (실 PostGIS, MSG-181 D8~D10). 게이트(enabled) 컨텍스트 없이 직접 생성해
 * remind() 를 직접 호출한다 (StaleTokenCleaner 테스트 선례). 대상 판정의 "어제"는 고정 Clock(2002-01-15
 * 20:00 KST)에서 앱이 계산해 바인딩하므로 실행 시점과 무관하게 결정적 — 공유 DB 의 타 유저 streaks 행도
 * 과거 고정 일자와 겹칠 수 없다. record 는 자체 트랜잭션 커밋이라 @Transactional 격리를 못 쓴다 —
 * 합성 유저 삭제(streaks·notifications CASCADE)로 걷어낸다.
 */
@SpringBootTest
@DisplayName("StreakRemindScheduler — 리마인드 대상 판정·REMIND 기록 (실 PostGIS)")
class StreakRemindSchedulerTest {

	/** 2002-01-15T11:00:00Z = 2002-01-15 20:00 KST — 발송 시각과 같은 저녁 시간대의 과거 고정 시각. */
	private static final Instant FIXED_INSTANT = Instant.parse("2002-01-15T11:00:00Z");
	private static final LocalDate TODAY_KST = LocalDate.of(2002, 1, 15);
	private static final String EVENT_KEY = "REMIND:" + TODAY_KST;

	@Autowired
	private StreakRepository streakRepository;

	@Autowired
	private NotificationCommandService notificationCommandService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private StreakRemindScheduler scheduler;
	private long userId;

	@BeforeEach
	void setUp() {
		scheduler = new StreakRemindScheduler(streakRepository, notificationCommandService,
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
		tx = new TransactionTemplate(txManager);
		String email = "remind-" + System.nanoTime() + "@example.com";
		tx.executeWithoutResult(status ->
			userId = userRepository.save(User.createLocalUser(email, "hash", "리마인드테스터")).getId());
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :userId")
				.setParameter("userId", userId)
				.executeUpdate());
	}

	@Test
	void 어제_기록하고_오늘_안_올린_사용자에게_REMIND가_기록된다() {
		seedStreak(5, TODAY_KST.minusDays(1));

		scheduler.remind();

		Object[] row = notificationRow();
		assertThat(row[0]).isEqualTo("REMIND");
		assertThat(row[1]).isEqualTo("스트릭이 오늘 자정에 끊겨요");
		assertThat(row[2]).isEqualTo("5일 연속 기록 중이에요. 영상 하나만 올리면 스트릭이 이어져요");
	}

	@Test
	void 오늘_이미_올린_사용자는_대상이_아니다() {
		seedStreak(3, TODAY_KST);

		scheduler.remind();

		assertThat(notificationCount()).isZero();
	}

	@Test
	void 이미_끊긴_사용자는_대상이_아니다() {
		seedStreak(3, TODAY_KST.minusDays(2));

		scheduler.remind();

		assertThat(notificationCount()).isZero();
	}

	@Test
	void 같은_날_재실행은_중복_기록되지_않는다() {
		seedStreak(5, TODAY_KST.minusDays(1));

		scheduler.remind();
		scheduler.remind();

		assertThat(notificationCount()).isEqualTo(1);
	}

	private void seedStreak(int currentCount, LocalDate lastRecordedDate) {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("""
					INSERT INTO streaks (user_id, current_count, max_count, last_recorded_date)
					VALUES (:userId, :current, :current, CAST(:lastDate AS date))
					""")
				.setParameter("userId", userId)
				.setParameter("current", currentCount)
				.setParameter("lastDate", lastRecordedDate.toString())
				.executeUpdate());
	}

	private long notificationCount() {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM notifications WHERE user_id = :userId AND event_key = :eventKey")
			.setParameter("userId", userId)
			.setParameter("eventKey", EVENT_KEY)
			.getSingleResult()).longValue();
	}

	/** category·title·body 스냅샷 — 단언은 호출부에서. */
	private Object[] notificationRow() {
		return (Object[]) em.createNativeQuery("""
				SELECT category, title, body FROM notifications
				WHERE user_id = :userId AND event_key = :eventKey
				""")
			.setParameter("userId", userId)
			.setParameter("eventKey", EVENT_KEY)
			.getSingleResult();
	}
}
