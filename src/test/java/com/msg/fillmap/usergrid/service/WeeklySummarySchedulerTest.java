package com.msg.fillmap.usergrid.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.usergrid.repository.UserGridRepository;

/**
 * 주간 활동 요약 배치 검증 (실 PostGIS, MSG-315 D1~D5). 게이트(enabled) 컨텍스트 없이 직접 생성해
 * summarize() 를 호출한다 (StreakRemindSchedulerTest 선례). 고정 시각은 KST 2002-01-13(일) 19시라
 * 창(2002-01-07 월 0시 이상 ~ 실행 시각 미만)에 공유 DB 의 현재 데이터가 들어올 수 없어 결정적이다.
 * 시드 타임스탬프는 임계값과 같은 규칙(KST 벽시계를 저장 존으로 환산)으로 넣어 CI 시간대와 무관하게 재현된다.
 * record 는 자체 트랜잭션 커밋이라 @Transactional 격리를 못 쓴다 — 합성 유저 삭제(videos·user_grids·
 * notifications CASCADE)로 걷어낸다.
 */
@SpringBootTest
@DisplayName("WeeklySummaryScheduler — 주간 집계 창·요약 문구·WEEKLY 기록 (실 PostGIS)")
class WeeklySummarySchedulerTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 2002-01-13T10:00:00Z = 2002-01-13(일) 19:00 KST — 발송 시각 그대로의 과거 고정 시각. */
	private static final Instant FIXED_INSTANT = Instant.parse("2002-01-13T10:00:00Z");

	/** 30분 뒤 재발화 (D1) — 같은 주라 이벤트 키가 같다. */
	private static final Instant RETRY_INSTANT = Instant.parse("2002-01-13T10:30:00Z");

	/** 창 하한 = KST 2002-01-07(월) 0시. */
	private static final LocalDateTime WEEK_START_KST = LocalDateTime.of(2002, 1, 7, 0, 0);

	private static final String EVENT_KEY = "WEEKLY:2002-W02";

	// 공해상(서해) 격자 — 어느 테스트도 안 쓰는 17705 행. grid_y 범위를 스캔하는 백필 테스트
	// (GridRegionCodeBackfillTest 39500~39503)와 겹치면 그쪽 갱신 행 수 단언이 깨지므로 대역을 분리한다.
	private static final long GY0 = 17705L;
	private static final long GX0 = 7956L;

	@Autowired
	private UserGridRepository userGridRepository;

	@Autowired
	private NotificationCommandService notificationCommandService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private WeeklySummaryScheduler scheduler;
	private long userId;
	private long otherUserId;
	private String gridId;

	@BeforeEach
	void setUp() {
		scheduler = new WeeklySummaryScheduler(userGridRepository, notificationCommandService,
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			userId = newUser("weekly");
			otherUserId = newUser("weekly-other");
			gridId = GridFixtures.seedGrid(em, GY0, GX0);
		});
	}

	@AfterEach
	void tearDown() {
		// 유저 삭제로 videos·user_grids·notifications 가 CASCADE 로 걷히고, 남는 grids 행도 지워 공유 DB 를 되돌린다.
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("DELETE FROM users WHERE id IN (:userId, :otherUserId)")
				.setParameter("userId", userId)
				.setParameter("otherUserId", otherUserId)
				.executeUpdate();
			em.createNativeQuery("DELETE FROM grids WHERE grid_id = :gridId")
				.setParameter("gridId", gridId)
				.executeUpdate();
		});
	}

	@Test
	@DisplayName("이번 주 수집과 업로드가 있으면 WEEKLY 요약이 기록된다 — 카테고리·이벤트 키·문구")
	void 이번_주_수집과_업로드가_있는_사용자에게_요약이_기록된다() {
		occupy(userId, WEEK_START_KST.plusDays(1));
		video(userId, WEEK_START_KST.plusDays(1), "ACTIVE");
		video(userId, WEEK_START_KST.plusDays(3), "ACTIVE");

		scheduler.summarize();

		Object[] row = notificationRow();
		assertThat(row[0]).isEqualTo("WEEKLY");
		assertThat(row[1]).isEqualTo("이번 주 활동 요약");
		assertThat(row[2]).isEqualTo("새 격자 1개를 수집하고 영상 2개를 올렸어요. 도감에서 확인해 보세요");
	}

	@Test
	@DisplayName("활동이 없는 사용자에게는 기록되지 않는다 — FR-6, 대상 조회가 곧 필터")
	void 활동이_없는_사용자에게는_기록되지_않는다() {
		scheduler.summarize();

		assertThat(notificationCount(userId)).isZero();
	}

	@Test
	@DisplayName("지난 주 활동만 있는 사용자는 대상이 아니다 — 창 하한 1초 전")
	void 지난_주_활동만_있는_사용자는_대상이_아니다() {
		occupy(userId, WEEK_START_KST.minusSeconds(1));
		video(userId, WEEK_START_KST.minusSeconds(1), "ACTIVE");

		scheduler.summarize();

		assertThat(notificationCount(userId)).isZero();
	}

	@Test
	@DisplayName("주 시작 정각 활동은 창에 포함된다 — KST 월요일 0시, 이상 비교 경계")
	void 주_시작_정각_활동은_창에_포함된다() {
		occupy(userId, WEEK_START_KST);
		video(userId, WEEK_START_KST, "ACTIVE");

		scheduler.summarize();

		assertThat(bodyOf(userId)).isEqualTo("새 격자 1개를 수집하고 영상 1개를 올렸어요. 도감에서 확인해 보세요");
	}

	@Test
	@DisplayName("재방문 업로드만 한 주는 영상 수만 문구에 담긴다 — 격자 0 갈래 (D5)")
	void 재방문_업로드만_한_주는_영상_수만_문구에_담긴다() {
		// 지난 주에 수집한 격자에 이번 주 재방문 업로드 2건 — 새 도감 행이 없다.
		occupy(userId, WEEK_START_KST.minusDays(3));
		video(userId, WEEK_START_KST.plusDays(2), "ACTIVE");
		video(userId, WEEK_START_KST.plusDays(4), "ACTIVE");

		scheduler.summarize();

		assertThat(bodyOf(userId)).isEqualTo("영상 2개를 올렸어요. 다음 주에는 새 격자도 채워 볼까요?");
	}

	@Test
	@DisplayName("삭제한 영상은 수치에서 빠진다 — DELETED 제외·BLINDED 포함 (MSG-152 D6 승계)")
	void 삭제한_영상은_수치에서_빠진다() {
		occupy(userId, WEEK_START_KST.plusDays(1));
		video(userId, WEEK_START_KST.plusDays(1), "ACTIVE");
		video(userId, WEEK_START_KST.plusDays(2), "BLINDED");
		video(userId, WEEK_START_KST.plusDays(3), "DELETED");

		scheduler.summarize();

		assertThat(bodyOf(userId)).isEqualTo("새 격자 1개를 수집하고 영상 2개를 올렸어요. 도감에서 확인해 보세요");
	}

	@Test
	@DisplayName("같은 주 재발화는 중복 기록되지 않는다 — 19시·19시 30분이 같은 이벤트 키 (D4)")
	void 같은_주_재발화는_중복_기록되지_않는다() {
		occupy(userId, WEEK_START_KST.plusDays(1));
		video(userId, WEEK_START_KST.plusDays(1), "ACTIVE");

		scheduler.summarize();
		new WeeklySummaryScheduler(userGridRepository, notificationCommandService,
			Clock.fixed(RETRY_INSTANT, ZoneOffset.UTC)).summarize();

		assertThat(notificationCount(userId)).isEqualTo(1);
	}

	@Test
	@DisplayName("한 사용자의 기록 실패가 다른 사용자의 요약을 막지 않는다 — 사용자 단위 격리 (D1)")
	void 한_사용자의_기록_실패가_다른_사용자의_요약을_막지_않는다() {
		occupy(userId, WEEK_START_KST.plusDays(1));
		video(userId, WEEK_START_KST.plusDays(1), "ACTIVE");
		occupy(otherUserId, WEEK_START_KST.plusDays(1));
		video(otherUserId, WEEK_START_KST.plusDays(1), "ACTIVE");
		// 순회 순서 무관하게 결정적이도록 "내 두 유저 중 첫 호출"에 실패를 주입한다 — record 는 단일 추상 메서드.
		AtomicBoolean firstCall = new AtomicBoolean(true);
		WeeklySummaryScheduler failingScheduler = new WeeklySummaryScheduler(userGridRepository,
			(user, category, eventKey, title, body) -> {
				if ((user == userId || user == otherUserId) && firstCall.getAndSet(false)) {
					throw new IllegalStateException("주입한 기록 실패");
				}
				notificationCommandService.record(user, category, eventKey, title, body);
			}, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

		failingScheduler.summarize();

		// 격리가 없으면 첫 실패가 루프를 끊어 0건 — 나머지 한 명은 기록돼야 한다.
		assertThat(notificationCount(userId) + notificationCount(otherUserId)).isEqualTo(1);
	}

	private long newUser(String prefix) {
		String email = prefix + "-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "요약테스터")).getId();
	}

	/** KST 벽시계를 저장 존(JVM 기본 존) 벽시계로 환산한다 — 임계값 바인딩과 같은 규칙 (D2). */
	private LocalDateTime stored(LocalDateTime kstWallClock) {
		return kstWallClock.atZone(KST).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

	private void occupy(long user, LocalDateTime firstCollectedAtKst) {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("""
					INSERT INTO user_grids (user_id, grid_id, video_count, first_collected_at, last_uploaded_at)
					VALUES (:userId, :gridId, 1, :at, :at)
					""")
				.setParameter("userId", user)
				.setParameter("gridId", gridId)
				.setParameter("at", stored(firstCollectedAtKst))
				.executeUpdate());
	}

	private void video(long user, LocalDateTime createdAtKst, String videoStatus) {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("""
					INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, status, created_at)
					VALUES (
						:userId, :gridId,
						ST_SetSRID(ST_MakePoint(125.01, 35.55), 4326)::geography,
						10, :at, :status, :at
					)
					""")
				.setParameter("userId", user)
				.setParameter("gridId", gridId)
				.setParameter("at", stored(createdAtKst))
				.setParameter("status", videoStatus)
				.executeUpdate());
	}

	private long notificationCount(long user) {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM notifications WHERE user_id = :userId AND event_key = :eventKey")
			.setParameter("userId", user)
			.setParameter("eventKey", EVENT_KEY)
			.getSingleResult()).longValue();
	}

	private String bodyOf(long user) {
		return (String) em.createNativeQuery(
				"SELECT body FROM notifications WHERE user_id = :userId AND event_key = :eventKey")
			.setParameter("userId", user)
			.setParameter("eventKey", EVENT_KEY)
			.getSingleResult();
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
