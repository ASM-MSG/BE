package com.msg.fillmap.notification.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.hotzone.config.HotZoneProperties;
import com.msg.fillmap.hotzone.service.HotZoneServiceImpl;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.usergrid.service.UserGridQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 핫구역 진입 검출 스케줄러 검증 (실 Redis + 실 PostGIS, MSG-181 D3~D7). 게이트(enabled) 컨텍스트
 * 없이 직접 생성해 detect() 를 직접 호출한다 (StaleTokenCleaner 테스트 선례). 핫구역 판정 입력은
 * HotZoneServiceImplTest 버킷 시딩 선례대로 실 Redis 버킷에 쓰되, 고정 Clock(2002-01-01)으로 버킷
 * 대역을 실서비스·타 테스트(2000·2001)와 격리한다. record 는 자체 트랜잭션 커밋이라 @Transactional
 * 격리를 못 쓴다 — 합성 유저 삭제(CASCADE)와 Redis 키 삭제로 걷어낸다.
 */
@SpringBootTest
@DisplayName("HotZoneEntryDetector — 진입 전이 검출·HOTZONE 알림 (실 Redis·실 PostGIS)")
class HotZoneEntryDetectorTest {

	/** 2002-01-01T00:00:00Z (= 09:00 KST) — 버킷 대역·KST 일자 모두 이 시각으로 고정. */
	private static final Instant FIXED_INSTANT = Instant.parse("2002-01-01T00:00:00Z");
	private static final long BUCKET_SECONDS = 21600L;
	private static final long CURRENT_BUCKET = FIXED_INSTANT.getEpochSecond() / BUCKET_SECONDS;
	private static final String TOP_KEY = "hotzone:top";
	private static final String SNAPSHOT_KEY = "notification:hotzone:last";
	private static final String KST_DATE = "2002-01-01";

	// 한국 bbox(D4) 안, 타 테스트 좌표대와 겹치지 않는 내륙 좌표 3점.
	private static final String GRID_A = GridEncoder.encode(36.01, 128.01);
	private static final String GRID_B = GridEncoder.encode(36.02, 128.02);
	private static final String GRID_C = GridEncoder.encode(36.03, 128.03);

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private UserGridQueryService userGridQueryService;

	@Autowired
	private NotificationCommandService notificationCommandService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private HotZoneEntryDetector detector;
	private long user1;
	private long user2;
	private String regionCode;

	@BeforeEach
	void setUp() {
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		detector = new HotZoneEntryDetector(
			// 진입 검출은 격자 ID 만 보므로 표시명 리졸버는 빈 스냅샷이면 충분하다 (MSG-341)
			new HotZoneServiceImpl(redisTemplate, new HotZoneProperties(50, 3),
				() -> new ZoneNameResolver(List.of()), fixedClock),
			userGridQueryService, notificationCommandService, redisTemplate, fixedClock);
		tx = new TransactionTemplate(txManager);
		cleanRedis();
		regionCode = "TN" + (System.nanoTime() % 100_000_000L);
		tx.executeWithoutResult(status -> {
			user1 = newUser("hotzone-1");
			user2 = newUser("hotzone-2");
			seedRegion();
			seedGrids();
		});
	}

	@AfterEach
	void tearDown() {
		cleanRedis();
		// 합성 유저 삭제 — user_grids·notifications 는 ON DELETE CASCADE. 합성 region 은 라벨 해제 후 삭제.
		// 시드한 grids 는 아무도 참조하지 않을 때만 지운다 — 예전엔 "잔존 무해"로 남겼으나 실제로 공유 로컬 DB 에
		// 무참조 행이 쌓였다(2026-08-06 유출 3행 실측). 참조가 있으면 남의 데이터이므로 건드리지 않는다.
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("DELETE FROM users WHERE id IN (:user1, :user2)")
				.setParameter("user1", user1)
				.setParameter("user2", user2)
				.executeUpdate();
			em.createNativeQuery("UPDATE grids SET region_code = NULL WHERE region_code = :code")
				.setParameter("code", regionCode)
				.executeUpdate();
			em.createNativeQuery("DELETE FROM regions WHERE region_code = :code")
				.setParameter("code", regionCode)
				.executeUpdate();
			em.createNativeQuery("""
				DELETE FROM grids g
				WHERE g.grid_id IN (:gridIds)
					AND NOT EXISTS (SELECT 1 FROM videos v WHERE v.grid_id = g.grid_id)
					AND NOT EXISTS (SELECT 1 FROM user_grids ug WHERE ug.grid_id = g.grid_id)
					AND NOT EXISTS (SELECT 1 FROM sponsor_ads sa WHERE sa.grid_id = g.grid_id)
				""")
				.setParameter("gridIds", List.of(GRID_A, GRID_B, GRID_C))
				.executeUpdate();
		});
	}

	@Test
	void 신규_진입_격자를_점령한_사용자마다_HOTZONE_알림이_기록된다() {
		tx.executeWithoutResult(status -> {
			GridFixtures.seedUserGrid(em, user1, GRID_A, 1);
			GridFixtures.seedUserGrid(em, user2, GRID_A, 1);
		});
		score(GRID_A, 3);

		detector.detect();

		String eventKey = "HOTZONE:" + KST_DATE + ":" + GRID_A;
		Object[] row = notificationRow(user1, eventKey);
		assertThat(row[0]).isEqualTo("HOTZONE");
		assertThat(row[1]).isEqualTo("내 격자가 핫구역에 들어왔어요");
		assertThat(row[2]).isEqualTo(regionCode + "에서 수집한 격자가 지금 인기예요. 지도에서 확인해 보세요");
		assertThat(notificationCount(user2, eventKey)).isEqualTo(1);
	}

	@Test
	void 직전_사이클에도_핫구역이던_격자는_다시_기록하지_않는다() {
		tx.executeWithoutResult(status -> GridFixtures.seedUserGrid(em, user1, GRID_A, 1));
		score(GRID_A, 3);
		redisTemplate.opsForSet().add(SNAPSHOT_KEY, GRID_A);   // 직전 사이클에도 핫구역

		detector.detect();

		assertThat(notificationCount(user1, "HOTZONE:" + KST_DATE + ":" + GRID_A)).isZero();
	}

	@Test
	void 같은_날_이탈_후_재진입은_중복_기록되지_않는다() {
		tx.executeWithoutResult(status -> GridFixtures.seedUserGrid(em, user1, GRID_A, 1));
		score(GRID_A, 3);
		detector.detect();                                     // 진입 — 기록 1건

		clearScores();                                         // 이탈 사이클 — 스냅숏이 비워진다
		detector.detect();
		score(GRID_A, 3);                                      // 같은 날 재진입
		detector.detect();

		assertThat(notificationCount(user1, "HOTZONE:" + KST_DATE + ":" + GRID_A)).isEqualTo(1);
	}

	@Test
	void 기록_후_스냅숏이_현재_집합으로_갱신된다() {
		score(GRID_A, 3);
		score(GRID_B, 4);

		detector.detect();

		assertThat(redisTemplate.opsForSet().members(SNAPSHOT_KEY)).containsExactlyInAnyOrder(GRID_A, GRID_B);
	}

	@Test
	void 점령_사용자가_없는_격자는_기록_없이_스냅숏만_갱신된다() {
		score(GRID_B, 3);

		detector.detect();

		assertThat(globalCount("HOTZONE:" + KST_DATE + ":" + GRID_B)).isZero();
		assertThat(redisTemplate.opsForSet().members(SNAPSHOT_KEY)).containsExactly(GRID_B);
	}

	@Test
	void 행정동_없는_격자는_폴백_문구로_기록된다() {
		tx.executeWithoutResult(status -> GridFixtures.seedUserGrid(em, user1, GRID_C, 1));
		score(GRID_C, 3);

		detector.detect();

		Object[] row = notificationRow(user1, "HOTZONE:" + KST_DATE + ":" + GRID_C);
		assertThat(row[2]).isEqualTo("내가 수집한 격자가 지금 인기예요. 지도에서 확인해 보세요");
	}

	private void cleanRedis() {
		redisTemplate.delete(TOP_KEY);
		redisTemplate.delete(SNAPSHOT_KEY);
		redisTemplate.delete("hotzone:" + CURRENT_BUCKET);
	}

	private long newUser(String prefix) {
		String email = prefix + "-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "핫구역테스터")).getId();
	}

	/** GRID_A 는 합성 region 라벨, GRID_C 는 무라벨(NULL) 고정 — 폴백 문구 경로의 결정성 확보. */
	private void seedGrids() {
		var a = GridEncoder.decode(GRID_A);
		var b = GridEncoder.decode(GRID_B);
		var c = GridEncoder.decode(GRID_C);
		GridFixtures.seedGrid(em, a.gridY(), a.gridX());
		GridFixtures.seedGrid(em, b.gridY(), b.gridX());
		GridFixtures.seedGrid(em, c.gridY(), c.gridX());
		em.createNativeQuery("UPDATE grids SET region_code = :code WHERE grid_id = :gridId")
			.setParameter("code", regionCode)
			.setParameter("gridId", GRID_A)
			.executeUpdate();
		em.createNativeQuery("UPDATE grids SET region_code = NULL WHERE grid_id = :gridId")
			.setParameter("gridId", GRID_C)
			.executeUpdate();
	}

	/** 합성 행정동 — 라벨은 seedGrids 가 명시 UPDATE 로 붙이므로 지오메트리는 더미다. */
	private void seedRegion() {
		em.createNativeQuery("""
				INSERT INTO regions (region_code, region_name, boundary_geom, total_grid_count)
				VALUES (
					:code, :code,
					ST_GeomFromText('MULTIPOLYGON(((128.0 36.0,128.0 36.1,128.1 36.1,128.1 36.0,128.0 36.0)))', 4326),
					10
				)
				""")
			.setParameter("code", regionCode)
			.executeUpdate();
	}

	private void score(String gridId, double value) {
		redisTemplate.opsForZSet().add("hotzone:" + CURRENT_BUCKET, gridId, value);
		redisTemplate.delete(TOP_KEY);   // 30s 캐시 무효화 — 다음 detect 가 신선한 버킷을 합산한다
	}

	private void clearScores() {
		redisTemplate.delete("hotzone:" + CURRENT_BUCKET);
		redisTemplate.delete(TOP_KEY);
	}

	private long notificationCount(long userId, String eventKey) {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM notifications WHERE user_id = :userId AND event_key = :eventKey")
			.setParameter("userId", userId)
			.setParameter("eventKey", eventKey)
			.getSingleResult()).longValue();
	}

	private long globalCount(String eventKey) {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM notifications WHERE event_key = :eventKey")
			.setParameter("eventKey", eventKey)
			.getSingleResult()).longValue();
	}

	/** category·title·body 스냅샷 — 단언은 호출부에서. */
	private Object[] notificationRow(long userId, String eventKey) {
		return (Object[]) em.createNativeQuery("""
				SELECT category, title, body FROM notifications
				WHERE user_id = :userId AND event_key = :eventKey
				""")
			.setParameter("userId", userId)
			.setParameter("eventKey", eventKey)
			.getSingleResult();
	}
}
