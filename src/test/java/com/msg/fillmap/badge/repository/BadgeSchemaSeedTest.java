package com.msg.fillmap.badge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * V9·V10 스키마·시딩·소급 검증 (실 PostGIS, MSG-239 모듈 1 + MSG-200 모듈 1). 공유 로컬 DB —
 * 합성 유저만 만들고 @Transactional 롤백, badges 마스터(V9·V10 시딩분)는 불가침·code 기준 단언만
 * 한다. 소급 블록은 V9 가 이미 적용된 DB 에선 재현 불가라 같은 SQL 을 테스트에서 재실행해
 * 검증한다(ON CONFLICT 멱등 — §D9). V10(스트릭 3종)은 시딩만·소급 없음(MSG-200 §D6).
 */
@SpringBootTest
@Transactional
@DisplayName("V9·V10 뱃지 스키마·시딩·소급 (실 PostGIS)")
class BadgeSchemaSeedTest {

	// 다른 테스트와 겹치지 않는 임의 기준점. 실제 grid_y/grid_x 는 GridEncoder 로 산출한다.
	private static final double 뚝섬_LAT = 37.5301;
	private static final double 뚝섬_LON = 127.0668;

	/** V9 소급 블록의 TOTAL_GRIDS SQL 과 동일 문장 (§D9 — 마이그레이션 템플릿이 곧 테스트 대상). */
	private static final String RETRO_TOTAL_GRIDS_SQL = """
		INSERT INTO user_badges (user_id, badge_id)
		SELECT m.user_id, b.id
		FROM (SELECT user_id, COUNT(*) AS metric FROM user_grids GROUP BY user_id) m
		JOIN badges b ON b.condition_type = 'TOTAL_GRIDS'
			AND (b.condition_value->>'value')::numeric <= m.metric
		ON CONFLICT DO NOTHING
		""";

	private static final String RETRO_UPLOAD_COUNT_SQL = """
		INSERT INTO user_badges (user_id, badge_id)
		SELECT m.user_id, b.id
		FROM (SELECT user_id, COUNT(*) AS metric FROM videos GROUP BY user_id) m
		JOIN badges b ON b.condition_type = 'UPLOAD_COUNT'
			AND (b.condition_value->>'value')::numeric <= m.metric
		ON CONFLICT DO NOTHING
		""";

	private static final String RETRO_REGION_PERCENT_SQL = """
		INSERT INTO user_badges (user_id, badge_id)
		SELECT m.user_id, b.id
		FROM (SELECT user_id, MAX(progress_rate) AS metric FROM region_stats GROUP BY user_id) m
		JOIN badges b ON b.condition_type = 'REGION_PERCENT'
			AND (b.condition_value->>'value')::numeric <= m.metric
		ON CONFLICT DO NOTHING
		""";

	@Autowired
	private EntityManager em;

	@Autowired
	private UserRepository userRepository;

	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		GridIndex base = GridEncoder.decode(GridEncoder.encode(뚝섬_LAT, 뚝섬_LON));
		baseY = base.gridY();
		baseX = base.gridX();
	}

	@Nested
	@DisplayName("마스터 시딩")
	class 마스터_시딩 {

		@Test
		@DisplayName("활성 3축 11종이 code 유일로 시딩되어 있다")
		void 활성_3축_11종이_code_유일로_시딩되어_있다() {
			List<String> codes = seededCodes("TOTAL_GRIDS", "UPLOAD_COUNT", "REGION_PERCENT");

			// code UNIQUE 제약이라 목록 일치 = 유일 시딩. 순서는 code 정렬 기준.
			assertThat(codes).containsExactlyInAnyOrder(
				"EXPLORER_1", "EXPLORER_10", "EXPLORER_50", "EXPLORER_100", "EXPLORER_500",
				"RECORDER_10", "RECORDER_50", "RECORDER_100",
				"REGION_MASTER_10", "REGION_MASTER_25", "REGION_MASTER_50");
		}

		@Test
		@DisplayName("스트릭 뱃지 3종이 시딩되어 있다 — V10 훅과 한 세트(MSG-200)")
		void 스트릭_뱃지_3종이_시딩되어_있다() {
			assertThat(seededCodes("STREAK_DAYS"))
				.containsExactlyInAnyOrder("STREAK_3", "STREAK_7", "STREAK_30");
		}

		@Test
		@DisplayName("미션과 스페셜 뱃지는 시딩되어 있지 않다 — 훅 없는 시딩은 획득 불가 뱃지 노출(§D2)")
		void 미션과_스페셜_뱃지는_시딩되어_있지_않다() {
			assertThat(seededCodes("MISSION_COUNT", "SPECIAL")).isEmpty();
		}

		@Test
		@DisplayName("스트릭 뱃지 소급 지급분이 없다 — 전원 0부터(MSG-200 §D6)")
		void 스트릭_뱃지_소급_지급분이_없다() throws IOException {
			// 전역 카운트 단언은 공유 DB 에서 상태 의존(실사용·DoD 수동 검증분이 남으면 영구 red)이라
			// 파일 정적 검증으로 대체(Codex 리뷰 반영) — 실제 주장은 "V10 에 소급 INSERT 블록이 없다"다.
			String v10 = new String(
				getClass().getClassLoader()
					.getResourceAsStream("db/migration/V10__streak_badges_seed.sql")
					.readAllBytes(),
				StandardCharsets.UTF_8);

			assertThat(v10).contains("INSERT INTO badges");
			assertThat(v10).doesNotContain("INSERT INTO user_badges");
		}

		@SuppressWarnings("unchecked")
		private List<String> seededCodes(String... conditionTypes) {
			// 값이 테스트 상수뿐이라 IN 목록을 인라인한다 — 네이티브 컬렉션 바인딩 의존 제거.
			String inList = "'" + String.join("', '", conditionTypes) + "'";
			return em.createNativeQuery(
					"SELECT code FROM badges WHERE condition_type IN (" + inList + ")")
				.getResultList();
		}
	}

	@Nested
	@DisplayName("스키마 확장")
	class 스키마_확장 {

		@Test
		@DisplayName("condition_type CHECK 가 MISSION_COUNT 를 허용한다 — V9 선반영(§D2)")
		void condition_type_CHECK가_MISSION_COUNT를_허용한다() {
			// 합성 row — 트랜잭션 롤백으로 사라진다. 마스터(V9 시딩분)는 건드리지 않는다.
			int inserted = em.createNativeQuery("""
					INSERT INTO badges (code, name, condition_type, condition_value)
					VALUES (:code, '테스트', 'MISSION_COUNT', '{"value": 1}')
					""")
				.setParameter("code", "TEST_MISSION_" + System.nanoTime())
				.executeUpdate();

			assertThat(inserted).isEqualTo(1);
		}

		@Test
		@DisplayName("대표 뱃지는 사용자당 rank 별 1개만 허용된다 — partial UNIQUE(§D6)")
		void 대표_뱃지는_사용자당_rank별_1개만_허용된다() {
			long me = newUser();
			insertUserBadge(me, badgeId("EXPLORER_1"), 1);

			assertThatThrownBy(() -> insertUserBadge(me, badgeId("EXPLORER_10"), 1))
				.isInstanceOf(PersistenceException.class)
				.hasMessageContaining("uq_user_badges_featured");
		}

		private void insertUserBadge(long userId, long badgeId, int featuredRank) {
			em.createNativeQuery("""
					INSERT INTO user_badges (user_id, badge_id, featured_rank)
					VALUES (:userId, :badgeId, :rank)
					""")
				.setParameter("userId", userId)
				.setParameter("badgeId", badgeId)
				.setParameter("rank", featuredRank)
				.executeUpdate();
		}
	}

	@Nested
	@DisplayName("소급 지급 (§D9)")
	class 소급_지급 {

		@Test
		@DisplayName("소급 SQL 은 기존 수집·업로드·수집률 데이터로 충족 티어를 일괄 지급한다")
		void 소급_SQL은_기존_수집_업로드_수집률_데이터로_충족_티어를_일괄_지급한다() {
			long me = newUser();
			seedGrids(me, 12);
			seedVideos(me, 10);
			seedRegionStats(me, "25.00");

			runRetroBlocks();

			// 격자 12 → EXPLORER_1·10, 영상 10 → RECORDER_10, 수집률 25% → REGION_MASTER_10·25 (FR-4·10).
			assertThat(earnedCodes(me)).containsExactlyInAnyOrder(
				"EXPLORER_1", "EXPLORER_10", "RECORDER_10", "REGION_MASTER_10", "REGION_MASTER_25");
		}

		@Test
		@DisplayName("소급 지급분은 notified_at 이 NULL 이다 — 미확인(§D8)")
		void 소급_지급분은_notified_at이_NULL이다() {
			long me = newUser();
			seedGrids(me, 1);

			runRetroBlocks();

			Number notified = (Number) em.createNativeQuery(
					"SELECT COUNT(*) FROM user_badges WHERE user_id = :me AND notified_at IS NOT NULL")
				.setParameter("me", me)
				.getSingleResult();
			assertThat(earnedCodes(me)).containsExactly("EXPLORER_1");
			assertThat(notified.longValue()).isZero();
		}

		@Test
		@DisplayName("소급 SQL 은 재실행해도 중복 row 가 생기지 않는다 — ON CONFLICT 멱등(§D9)")
		void 소급_SQL은_재실행해도_중복_row가_생기지_않는다() {
			long me = newUser();
			seedGrids(me, 12);

			runRetroBlocks();
			runRetroBlocks();

			assertThat(earnedCodes(me)).containsExactlyInAnyOrder("EXPLORER_1", "EXPLORER_10");
		}

		private void runRetroBlocks() {
			em.createNativeQuery(RETRO_TOTAL_GRIDS_SQL).executeUpdate();
			em.createNativeQuery(RETRO_UPLOAD_COUNT_SQL).executeUpdate();
			em.createNativeQuery(RETRO_REGION_PERCENT_SQL).executeUpdate();
		}
	}

	private long newUser() {
		String email = "badge-seed-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "뱃지시드테스터")).getId();
	}

	private long badgeId(String code) {
		return ((Number) em.createNativeQuery("SELECT id FROM badges WHERE code = :code")
			.setParameter("code", code)
			.getSingleResult()).longValue();
	}

	@SuppressWarnings("unchecked")
	private List<String> earnedCodes(long userId) {
		return em.createNativeQuery("""
				SELECT b.code FROM user_badges ub JOIN badges b ON b.id = ub.badge_id
				WHERE ub.user_id = :userId
				""")
			.setParameter("userId", userId)
			.getResultList();
	}

	private void seedGrids(long userId, int count) {
		for (int i = 0; i < count; i++) {
			String gridId = GridFixtures.seedGrid(em, baseY + i, baseX);
			GridFixtures.seedUserGrid(em, userId, gridId, 1);
		}
	}

	private void seedVideos(long userId, int count) {
		String gridId = GridFixtures.seedGrid(em, baseY, baseX);
		for (int i = 0; i < count; i++) {
			em.createNativeQuery("""
					INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, status)
					VALUES (
						:userId, :gridId,
						ST_SetSRID(ST_MakePoint(127.06, 37.53), 4326)::geography,
						10, now(), 'ACTIVE'
					)
					""")
				.setParameter("userId", userId)
				.setParameter("gridId", gridId)
				.executeUpdate();
		}
	}

	/** regions FK 를 위해 합성 행정동을 먼저 시딩하고 region_stats 에 수집률을 물질화 값으로 넣는다. */
	private void seedRegionStats(long userId, String progressRate) {
		String regionCode = "TB" + (System.nanoTime() % 100_000_000L);
		em.createNativeQuery("""
				INSERT INTO regions (region_code, region_name, boundary_geom)
				VALUES (
					:code, :code,
					ST_GeomFromText('MULTIPOLYGON(((128.4 34.1,128.4 34.2,128.5 34.2,128.5 34.1,128.4 34.1)))', 4326)
				)
				ON CONFLICT (region_code) DO NOTHING
				""")
			.setParameter("code", regionCode)
			.executeUpdate();
		em.createNativeQuery("""
				INSERT INTO region_stats (user_id, region_code, collected_count, total_count, progress_rate)
				VALUES (:userId, :code, 1, 4, CAST(:rate AS numeric))
				""")
			.setParameter("userId", userId)
			.setParameter("code", regionCode)
			.setParameter("rate", progressRate)
			.executeUpdate();
	}
}
