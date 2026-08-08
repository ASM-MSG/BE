package com.msg.fillmap.region.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * region_stats recompute UPSERT (MSG-155 모듈 1, 실 PostGIS). 격리: 실존하지 않는 999 대역 region_code 와
 * 서해 공해상(육상 실데이터와 겹치지 않는) 소형 합성 폴리곤, 그 폴리곤 안/밖에 중심점이 오도록 잡은 합성 grids·user_grids +
 * @Transactional 롤백만 쓴다. regions/grids/user_grids/region_stats 를 truncate 하지 않는다(공유 DB).
 */
@SpringBootTest
@Transactional
@DisplayName("RegionRepository.refreshRegionStats — recompute UPSERT (합성 폴리곤·롤백 격리)")
class RegionStatsRecomputeTest {

	// 서해 공해상(lat ≈36.0, lon ≈125.0) 기준 격자 인덱스 — 육상 실데이터 행정동과 겹치지 않는 좌표 대역.
	private static final long GY0 = 17810L;
	private static final long GX0 = 7746L;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long user1;
	private long user2;

	@BeforeEach
	void setUp() {
		user1 = userRepository.save(User.createLocalUser("stats-u1@example.com", "hash", "유저1")).getId();
		user2 = userRepository.save(User.createLocalUser("stats-u2@example.com", "hash", "유저2")).getId();
	}

	/** 격자 인덱스 경계 [minGy,maxGy)×[minGx,maxGx) 를 통째로 덮는 합성 행정동을 upsert 한다. */
	private void seedRegion(String code, long minGy, long maxGy, long minGx, long maxGx) {
		String polygon = cellBlockPolygonJson(minGy, maxGy, minGx, maxGx);
		regionRepository.upsert(code, "합성동", code.substring(0, 5), polygon, CELL_AREA_M2);
	}

	private String occupy(long userId, long gy, long gx) {
		// seedLabeledGrid: region 을 먼저 시드한 뒤 호출하면 grids.region_code 가 탄생 시 라벨된다(프로덕션 upsertGrid 재현).
		// equi refreshRegionStats 가 이 저장 라벨을 읽는다 — 무라벨(해안)이면 서브쿼리가 NULL 이라 가드로 no-op.
		String gridId = GridFixtures.seedLabeledGrid(em, gy, gx);
		GridFixtures.seedUserGrid(em, userId, gridId, 1);
		return gridId;
	}

	private void rollbackOccupation(long userId, String gridId) {
		em.createNativeQuery("DELETE FROM user_grids WHERE user_id = :u AND grid_id = :g")
			.setParameter("u", userId).setParameter("g", gridId).executeUpdate();
	}

	private Integer collected(long userId, String code) {
		List<?> rows = em.createNativeQuery(
				"SELECT collected_count FROM region_stats WHERE user_id = :u AND region_code = :c")
			.setParameter("u", userId).setParameter("c", code).getResultList();
		return rows.isEmpty() ? null : ((Number) rows.get(0)).intValue();
	}

	private long statsRowCount(long userId) {
		Number n = (Number) em.createNativeQuery(
				"SELECT COUNT(*) FROM region_stats WHERE user_id = :u")
			.setParameter("u", userId).getSingleResult();
		return n.longValue();
	}

	@Test
	@DisplayName("점령한 격자의 중심점 행정동에 region_stats 가 생성되고 collected_count 가 1 이다")
	void 점령한_격자의_중심점_행정동에_region_stats가_생성되고_collected_count가_1이다() {
		String a = syntheticCode("1");
		seedRegion(a, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = occupy(user1, GY0, GX0);

		regionRepository.refreshRegionStats(user1, grid);

		assertThat(collected(user1, a)).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 행정동에 격자를 더 점령하면 collected_count 가 점령 격자 수와 일치한다")
	void 같은_행정동에_격자를_더_점령하면_collected_count가_점령격자수와_일치한다() {
		String a = syntheticCode("1");
		seedRegion(a, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = occupy(user1, GY0, GX0);
		occupy(user1, GY0, GX0 + 1);
		occupy(user1, GY0 + 1, GX0);

		regionRepository.refreshRegionStats(user1, grid);

		assertThat(collected(user1, a)).isEqualTo(3);
	}

	@Test
	@DisplayName("경계 격자에 양쪽 행정동 좌표 영상이 2개여도 중심점 기준 한 행정동에만 1 카운트된다")
	void 경계_격자에_양쪽_행정동_좌표_영상이_2개여도_중심점_기준_한_행정동에만_1_카운트된다() {
		// 격자 (GY0,GX0) 의 동서를 가르는 경계선을 셀 내부(중심점 동편, 0.7 지점)에 둔다 — 격자가 A|B 를 물리적으로 걸친다.
		double borderLon = GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.7).lon();
		double minLat = GridFixtures.pointAt(GY0 - 1, GX0 + 0.5).lat();
		double maxLat = GridFixtures.pointAt(GY0 + 2, GX0 + 0.5).lat();
		String a = syntheticCode("1");
		String b = syntheticCode("2");
		regionRepository.upsert(a, "합성동", a.substring(0, 5),
			rectanglePolygonJson(GridFixtures.pointAt(GY0 + 0.5, GX0 - 2).lon(), minLat, borderLon, maxLat),
			CELL_AREA_M2);
		regionRepository.upsert(b, "합성동", b.substring(0, 5),
			rectanglePolygonJson(borderLon, minLat, GridFixtures.pointAt(GY0 + 0.5, GX0 + 3).lon(), maxLat),
			CELL_AREA_M2);

		String grid = occupy(user1, GY0, GX0);

		regionRepository.refreshRegionStats(user1, grid);

		// 중심점(0.5 < 0.7)은 A 안 → A 에만 1, B 는 행이 없다(이중 카운트 없음).
		assertThat(collected(user1, a)).isEqualTo(1);
		assertThat(collected(user1, b)).isNull();
	}

	@Test
	@DisplayName("점령이 롤백되면 refresh 후 collected_count 가 감소한다")
	void 점령이_롤백되면_refresh_후_collected_count가_감소한다() {
		String a = syntheticCode("1");
		seedRegion(a, GY0, GY0 + 3, GX0, GX0 + 3);
		String g1 = occupy(user1, GY0, GX0);
		String g2 = occupy(user1, GY0, GX0 + 1);
		regionRepository.refreshRegionStats(user1, g1);
		assertThat(collected(user1, a)).isEqualTo(2);

		rollbackOccupation(user1, g2);
		regionRepository.refreshRegionStats(user1, g2);

		assertThat(collected(user1, a)).isEqualTo(1);
	}

	@Test
	@DisplayName("행정동의 마지막 격자를 롤백하면 collected_count 가 0 으로 UPSERT 된다 (row 유지)")
	void 행정동의_마지막_격자를_롤백하면_collected_count가_0으로_UPSERT된다() {
		String a = syntheticCode("1");
		seedRegion(a, GY0, GY0 + 3, GX0, GX0 + 3);
		String g1 = occupy(user1, GY0, GX0);
		regionRepository.refreshRegionStats(user1, g1);

		// user_grids 만 삭제(롤백) — grids row 는 남아 중심점 판정이 여전히 가능하다.
		rollbackOccupation(user1, g1);
		regionRepository.refreshRegionStats(user1, g1);

		assertThat(collected(user1, a)).isZero();
	}

	@Test
	@DisplayName("중심점이 어느 행정동에도 안 속하는 해안 격자는 어떤 region_stats 도 바꾸지 않는다")
	void 중심점이_어느_행정동에도_안속하는_해안_격자는_어떤_region_stats도_바꾸지_않는다() {
		seedRegion(syntheticCode("1"), GY0, GY0 + 3, GX0, GX0 + 3);
		// 행정동 밖(북쪽 100칸)·공해상 격자 — 중심점을 덮는 행정동이 없다.
		String coastal = occupy(user1, GY0 + 100, GX0);

		regionRepository.refreshRegionStats(user1, coastal);

		assertThat(statsRowCount(user1)).isZero();
	}

	@Test
	@DisplayName("total_count 는 regions.total_grid_count 와 같고 progress_rate 는 collected*100/total 이다")
	void total_count는_regions_total_grid_count와_같고_progress_rate는_collected_100_나누기_total이다() {
		String a = syntheticCode("1");
		seedRegion(a, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = occupy(user1, GY0, GX0);

		regionRepository.refreshRegionStats(user1, grid);

		Number regionTotal = (Number) em.createNativeQuery(
				"SELECT total_grid_count FROM regions WHERE region_code = :c")
			.setParameter("c", a).getSingleResult();
		Object[] stats = (Object[]) em.createNativeQuery(
				"SELECT total_count, progress_rate FROM region_stats WHERE user_id = :u AND region_code = :c")
			.setParameter("u", user1).setParameter("c", a).getSingleResult();

		int total = regionTotal.intValue();
		BigDecimal expectedRate = BigDecimal.valueOf(1L * 100)
			.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
		assertThat(((Number) stats[0]).intValue()).isEqualTo(total);
		assertThat((BigDecimal) stats[1]).isEqualByComparingTo(expectedRate);
	}

	@Test
	@DisplayName("같은 이벤트를 두 번 refresh 해도 collected_count 가 진값과 같다 (멱등)")
	void 같은_이벤트를_두번_refresh해도_collected_count가_진값과_같다() {
		String a = syntheticCode("1");
		seedRegion(a, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = occupy(user1, GY0, GX0);

		regionRepository.refreshRegionStats(user1, grid);
		regionRepository.refreshRegionStats(user1, grid);

		assertThat(collected(user1, a)).isEqualTo(1);
	}

	@Test
	@DisplayName("다른 사용자의 점령은 내 collected_count 에 섞이지 않는다")
	void 다른_사용자의_점령은_내_collected_count에_섞이지_않는다() {
		String a = syntheticCode("1");
		seedRegion(a, GY0, GY0 + 3, GX0, GX0 + 3);
		String g1 = occupy(user1, GY0, GX0);
		occupy(user1, GY0, GX0 + 1);
		occupy(user2, GY0 + 1, GX0);
		occupy(user2, GY0 + 1, GX0 + 1);
		occupy(user2, GY0 + 2, GX0);

		regionRepository.refreshRegionStats(user1, g1);

		assertThat(collected(user1, a)).isEqualTo(2);
	}

	@Test
	@DisplayName("중심점을 두 행정동이 동시에 덮으면 region_code 오름차순 첫 행정동에만 결정적으로 귀속된다")
	void 중심점을_두_행정동이_동시에_덮으면_오름차순_첫_행정동에만_결정적으로_귀속된다() {
		String first = syntheticCode("1");
		String second = syntheticCode("2");
		// 동일한 폴리곤을 두 행정동으로 등록 — 격자 중심점을 둘 다 덮는 다중매칭 상황을 강제한다.
		seedRegion(first, GY0, GY0 + 3, GX0, GX0 + 3);
		seedRegion(second, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = occupy(user1, GY0, GX0);

		regionRepository.refreshRegionStats(user1, grid);
		regionRepository.refreshRegionStats(user1, grid);

		assertThat(collected(user1, first)).isEqualTo(1);
		assertThat(collected(user1, second)).isNull();
		assertThat(statsRowCount(user1)).isEqualTo(1L);
	}

	@Test
	@DisplayName("무라벨 격자는 같은 행정동 recompute 집계에서 제외되고 백필 후 다시 포함된다")
	void 무라벨_격자는_같은_행정동_recompute_집계에서_제외되고_백필_후_다시_포함된다() {
		String a = syntheticCode("1");
		// g1 을 region 시드 전에 seedGrid → region_code NULL (시더 이전에 태어난 격자 재현).
		String g1 = GridFixtures.seedGrid(em, GY0, GX0);
		seedRegion(a, GY0, GY0 + 3, GX0, GX0 + 3);
		// g2 는 region 시드 후 seedLabeledGrid → 탄생 시 라벨 A.
		String g2 = GridFixtures.seedLabeledGrid(em, GY0 + 1, GX0);
		GridFixtures.seedUserGrid(em, user1, g1, 1);
		GridFixtures.seedUserGrid(em, user1, g2, 1);

		regionRepository.refreshRegionStats(user1, g2);

		// 라벨된 g2 만 집계 — NULL 인 g1 은 equi 가 해안 취급해 제외(§D2 ②③).
		assertThat(collected(user1, a)).isEqualTo(1);

		// 시더 보정 백필이 g1 을 라벨 A → 등가 복원(발산 창이 닫힘을 증명, §D2 결론).
		regionRepository.backfillGridRegionCodes();
		regionRepository.refreshRegionStats(user1, g2);

		assertThat(collected(user1, a)).isEqualTo(2);
	}
}
