package com.msg.fillmap.badge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.badge.repository.BadgeRepository;
import com.msg.fillmap.badge.repository.UserBadgeRepository;
import com.msg.fillmap.notification.repository.NotificationRepository;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 뱃지 임박(BADGE_NEAR) 판정·상한·멱등 통합 검증 (실 PostGIS, docs/spec/MSG-314.md). 생애 1회(D2)·하루
 * 1건(D3)·일 경계(D4)를 실제 DB 제약과 쿼리로 관찰해야 하므로 @Transactional 격리를 못 쓴다 —
 * 합성 유저를 커밋해 두고 @AfterEach 에서 users 지정 삭제로 걷어낸다 (user_badges·notifications
 * 모두 ON DELETE CASCADE — BadgeNotificationIntegrationTest 선례).
 *
 * <p>서비스는 전진 가능한 시계를 주입해 직접 조립한다 (KST 일 경계 전진용 — 프로덕션 생성자는
 * Clock.systemUTC() 고정이라 빈으로는 못 돌린다). 수동 조립이라 @Transactional 프록시가 없으므로
 * 모든 호출을 TransactionTemplate 로 감싼다 — 전파 REQUIRED 인 프로덕션 배선(업로드 트랜잭션 참여)과
 * 같은 트랜잭션 형상이다. 시계 기준은 실제 now: created_at 은 DB 실시각(statement_timestamp)으로
 * 쓰이므로 같은 날 판정이 실시각과 정렬돼야 결정적이다.
 */
@SpringBootTest
@DisplayName("뱃지 임박 알림 — 판정·생애 1회·하루 1건 (실 PostGIS)")
class BadgeNearMissIntegrationTest {

	@Autowired
	private BadgeRepository badgeRepository;

	@Autowired
	private UserBadgeRepository userBadgeRepository;

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
	private SteppingClock clock;
	private BadgeAwardServiceImpl badgeAwardService;
	private long userId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		clock = new SteppingClock();
		badgeAwardService = new BadgeAwardServiceImpl(badgeRepository, userBadgeRepository,
			notificationCommandService, notificationRepository, clock);
		String email = "badge-near-" + System.nanoTime() + "@example.com";
		tx.executeWithoutResult(status ->
			userId = userRepository.save(User.createLocalUser(email, "hash", "임박테스터")).getId());
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :userId")
				.setParameter("userId", userId)
				.executeUpdate());
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("다음 티어까지 1개 남으면 BADGE_NEAR 알림이 기록된다 — 지급 후보 0건 경로에서도 (D1·D5)")
	void 다음_티어까지_1개_남은_업로드는_임박_알림을_기록한다() {
		// 9번째 업로드: RECORDER_10(10)까지 정확히 1 남음 — 지급 후보는 0건이라 조기 return 이 있었다면 죽는 경로.
		List<EarnedBadgeResponseDto> earned = award(BadgeConditionType.UPLOAD_COUNT, 9);

		assertThat(earned).isEmpty();
		assertThat(nearCount()).isEqualTo(1);
		Object[] row = notificationRow("BADGE_NEAR:" + badgeId("RECORDER_10"));
		assertThat(row[0]).isEqualTo("BADGE");
		assertThat(row[1]).isEqualTo("뱃지 획득 임박");
		assertThat(row[2]).isEqualTo("'기록러 I' 뱃지까지 딱 하나 남았어요");
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("티어에 도달하면 획득만 기록한다 — 획득·임박 서로소 (D1)")
	void 티어에_도달한_업로드는_획득만_기록하고_임박은_기록하지_않는다() {
		List<EarnedBadgeResponseDto> earned = award(BadgeConditionType.UPLOAD_COUNT, 10);

		assertThat(earned).extracting(EarnedBadgeResponseDto::code).containsExactly("RECORDER_10");
		assertThat(nearCount()).isZero();
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("같은 뱃지의 임박은 생애 1회 — 다음 날에도 같은 키 재기록은 0행으로 무시 (D2)")
	void 같은_뱃지의_임박_알림은_생애_1회만_기록된다() {
		award(BadgeConditionType.UPLOAD_COUNT, 9);
		// 하루 전진 — 일 상한이 아니라 (user_id, event_key) 유니크가 막는다는 것을 분리 검증한다.
		clock.plusDays(1);

		award(BadgeConditionType.UPLOAD_COUNT, 9);

		assertThat(nearCount()).isEqualTo(1);
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("점령 롤백으로 임박 상태를 다시 만들어도 재기록되지 않는다 — FR-4 '오가도 반복 없음'")
	void 점령_롤백으로_임박_상태를_다시_만들어도_재기록되지_않는다() {
		// 격자 9개 수집: EXPLORER_10 임박.
		award(BadgeConditionType.TOTAL_GRIDS, 9);
		// 영상 삭제 → 점령 롤백(격자 8) → 재점령으로 같은 날 다시 9: 상한이 먼저 걸러도 행은 그대로 1.
		award(BadgeConditionType.TOTAL_GRIDS, 9);
		// 다음 날 다시 임박 구간 재진입: 상한은 통과하지만 이미 있는 키라 조용히 무시된다.
		clock.plusDays(1);
		award(BadgeConditionType.TOTAL_GRIDS, 9);

		assertThat(nearCount()).isEqualTo(1);
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("하루에 임박 알림은 사용자당 1건 — 서로 다른 뱃지가 같은 KST 날에 임박해도 (D3)")
	void 하루에_임박_알림은_사용자당_1건만_기록된다() {
		award(BadgeConditionType.UPLOAD_COUNT, 9);

		award(BadgeConditionType.TOTAL_GRIDS, 9);

		assertThat(nearCount()).isEqualTo(1);
		assertThat(notificationRow("BADGE_NEAR:" + badgeId("RECORDER_10"))).isNotNull();
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("한 업로드에서 두 축이 동시에 임박해도 1건 — 같은 트랜잭션의 미커밋 행을 보고 스킵 (D3)")
	void 한_업로드에서_두_축이_동시에_임박해도_1건만_기록된다() {
		// 업로드 트랜잭션 하나에서 스트릭·업로드 축 판정이 연속 호출되는 형상 — 뒤 호출의 존재 확인이
		// 같은 트랜잭션의 미커밋 BADGE_NEAR 행을 보고 스킵해야 한다.
		tx.executeWithoutResult(status -> {
			badgeAwardService.award(userId, BadgeConditionType.UPLOAD_COUNT, BigDecimal.valueOf(9));
			badgeAwardService.award(userId, BadgeConditionType.STREAK_DAYS, BigDecimal.valueOf(2));
		});

		assertThat(nearCount()).isEqualTo(1);
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("미션 두 축이 동시에 임박해도 알림은 하루 한 건이다 — 종류별 축 분리 후에도(MSG-363 §D5)")
	void 미션_두_축이_동시에_임박해도_알림은_하루_한_건이다() {
		// 한 업로드가 축제·팝업을 동시에 완료하고 둘 다 다음 티어(3)까지 1 남은 형상.
		tx.executeWithoutResult(status -> {
			badgeAwardService.award(userId, BadgeConditionType.EVENT_COUNT, BigDecimal.valueOf(2));
			badgeAwardService.award(userId, BadgeConditionType.POPUP_COUNT, BigDecimal.valueOf(2));
		});

		assertThat(nearCount()).isEqualTo(1);
		assertThat(notificationRow("BADGE_NEAR:" + badgeId("EVENT_3"))).isNotNull();
	}

	// 검증: FR-BADGE-10, FR-BADGE-12
	@Test
	@DisplayName("은퇴한 뱃지는 임박 후보로 조회되지 않는다 — findNearMiss 의 retired_at 필터(MSG-363 §D2)")
	void 은퇴한_뱃지는_임박_후보로_조회되지_않는다() {
		// 임박 축 allowlist 안(EVENT_COUNT)에 등식(6 + 1 = 7)이 성립하는 은퇴 뱃지를 심는다 — 축 가드를
		// 통과해 findNearMiss 까지 내려가므로 쿼리의 retired_at 필터가 없으면 기록돼 버린다. 실 티어
		// (1·3·10)와 값이 겹치지 않게 7 을 쓴다. 공유 DB 라 finally 로 반드시 걷어낸다.
		tx.executeWithoutResult(status -> em.createNativeQuery("""
				INSERT INTO badges (code, name, description, condition_type, condition_value, retired_at)
				VALUES ('EVENT_M363_TEST', '은퇴합성', '테스트 전용', 'EVENT_COUNT', '{"value": 7}',
					now() AT TIME ZONE 'UTC')
				ON CONFLICT (code) DO NOTHING
				""").executeUpdate());
		try {
			award(BadgeConditionType.EVENT_COUNT, 6);

			assertThat(nearCount()).isZero();
		} finally {
			tx.executeWithoutResult(status ->
				em.createNativeQuery("DELETE FROM badges WHERE code = 'EVENT_M363_TEST'").executeUpdate());
		}
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("다음 날에는 다른 뱃지의 임박이 기록될 수 있다 — KST 일 경계 전진 (D4)")
	void 다음_날에는_다른_뱃지의_임박이_기록될_수_있다() {
		award(BadgeConditionType.UPLOAD_COUNT, 9);
		clock.plusDays(1);

		award(BadgeConditionType.TOTAL_GRIDS, 9);

		assertThat(nearCount()).isEqualTo(2);
		assertThat(notificationRow("BADGE_NEAR:" + badgeId("EXPLORER_10"))).isNotNull();
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("수집률 축에서는 임박을 판정하지 않는다 — 등식이 성립하는 값이어도 (D1 축 범위)")
	void 수집률_축에서는_임박을_판정하지_않는다() {
		// 9% + 1 = REGION_MASTER_10(10) 로 등식 자체는 성립하는 값 — type 가드가 없으면 기록돼 버린다.
		award(BadgeConditionType.REGION_PERCENT, 9);

		assertThat(nearCount()).isZero();
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("SPECIAL 축에서는 임박을 판정하지 않는다 — allowlist 미포함 축 (D1, Codex 1R P2)")
	void SPECIAL_축에서는_임박을_판정하지_않는다() {
		// 등식(9 + 1 = 10)이 성립하는 합성 SPECIAL 뱃지를 심는다 — REGION_PERCENT 만 막는 blocklist
		// 구현이면 숫자 임박 쿼리에 흘러들어 기록돼 버린다. 공유 DB 라 finally 로 반드시 걷어낸다.
		tx.executeWithoutResult(status -> em.createNativeQuery("""
				INSERT INTO badges (code, name, description, condition_type, condition_value)
				VALUES ('SPECIAL_M314_TEST', '임박합성', '테스트 전용', 'SPECIAL', '{"value": 10}')
				ON CONFLICT (code) DO NOTHING
				""").executeUpdate());
		try {
			award(BadgeConditionType.SPECIAL, 9);

			assertThat(nearCount()).isZero();
		} finally {
			tx.executeWithoutResult(status ->
				em.createNativeQuery("DELETE FROM badges WHERE code = 'SPECIAL_M314_TEST'").executeUpdate());
		}
	}

	@Test
	@DisplayName("임박 후보가 없는 업로드는 잠금 없이 지나간다 — 다수 경로의 비용 상한 (D3)")
	void 임박_후보가_없는_업로드는_잠금_없이_지나간다() {
		// advisory xact 잠금은 트랜잭션 끝까지 유지되므로, 같은 트랜잭션 안에서 pg_locks 로 보유 여부를 관찰한다.
		tx.executeWithoutResult(status -> {
			badgeAwardService.award(userId, BadgeConditionType.UPLOAD_COUNT, BigDecimal.valueOf(5));
			assertThat(advisoryLockCount()).isZero();
			// 관찰 채널 검증(양성 대조): 임박 경로는 같은 방법으로 잠금이 잡힌다.
			badgeAwardService.award(userId, BadgeConditionType.UPLOAD_COUNT, BigDecimal.valueOf(9));
			assertThat(advisoryLockCount()).isEqualTo(1);
		});
	}

	// 검증: FR-BADGE-10
	@Test
	@DisplayName("업로드 트랜잭션이 롤백되면 임박 알림도 남지 않는다 — 같은 커밋 원자성 (성공 기준 1)")
	void 업로드_트랜잭션이_롤백되면_임박_알림도_남지_않는다() {
		tx.executeWithoutResult(status -> {
			badgeAwardService.award(userId, BadgeConditionType.UPLOAD_COUNT, BigDecimal.valueOf(9));
			status.setRollbackOnly();
		});

		assertThat(nearCount()).isZero();
	}

	private List<EarnedBadgeResponseDto> award(BadgeConditionType type, int metric) {
		return tx.execute(status -> badgeAwardService.award(userId, type, BigDecimal.valueOf(metric)));
	}

	private long nearCount() {
		return ((Number) em.createNativeQuery("""
				SELECT count(*) FROM notifications WHERE user_id = :userId AND event_key LIKE 'BADGE_NEAR:%'
				""")
			.setParameter("userId", userId)
			.getSingleResult()).longValue();
	}

	/** category·title·body 스냅샷 — 단언은 호출부에서 (BadgeNotificationIntegrationTest 선례). */
	private Object[] notificationRow(String eventKey) {
		return (Object[]) em.createNativeQuery("""
				SELECT category, title, body FROM notifications
				WHERE user_id = :userId AND event_key = :eventKey
				""")
			.setParameter("userId", userId)
			.setParameter("eventKey", eventKey)
			.getSingleResult();
	}

	private long badgeId(String code) {
		return ((Number) em.createNativeQuery("SELECT id FROM badges WHERE code = :code")
			.setParameter("code", code)
			.getSingleResult()).longValue();
	}

	/** 현재 트랜잭션(내 백엔드 pid)이 보유한 advisory 잠금 수. */
	private long advisoryLockCount() {
		return ((Number) em.createNativeQuery("""
				SELECT count(*) FROM pg_locks
				WHERE locktype = 'advisory' AND granted AND pid = pg_backend_pid()
				""")
			.getSingleResult()).longValue();
	}

	/**
	 * 전진 가능 시계 — 기준은 실제 now(같은 날 판정이 DB statement_timestamp 와 정렬), 일 경계 테스트가
	 * plusDays 로 KST 날짜를 넘긴다.
	 */
	private static final class SteppingClock extends Clock {

		private Instant instant = Instant.now();

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

		void plusDays(long days) {
			instant = instant.plus(Duration.ofDays(days));
		}
	}
}
