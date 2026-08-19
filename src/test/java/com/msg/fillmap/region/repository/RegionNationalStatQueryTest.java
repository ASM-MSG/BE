package com.msg.fillmap.region.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.squareKm2Json;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * RegionRepository.findNationalStat — 전국 탐험률 재료 합산 native (MSG-406 모듈 1, 실 PostGIS).
 * 격리: 신규 유저 + 999 대역 합성 regions/region_stats + @Transactional 롤백. 분모는 전국 합산이라
 * 공유 DB 의 실데이터가 섞이므로 시드 전 기준값(baseline)을 재고 증가분만 검증한다(truncate 하지 않는다).
 */
@SpringBootTest
@Transactional
@DisplayName("RegionRepository.findNationalStat — 전국 분자/분모 합산 (합성 row·롤백 격리)")
class RegionNationalStatQueryTest {

	// 서해 공해상 기준 격자 인덱스 — 육상 실데이터 행정동과 겹치지 않는다(RegionStatsRecomputeTest 와 같은 대역).
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
		user1 = userRepository.save(User.createLocalUser("nat-q1@example.com", "hash", "유저1")).getId();
		user2 = userRepository.save(User.createLocalUser("nat-q2@example.com", "hash", "유저2")).getId();
	}

	/** 999 대역 합성 행정동. 조회가 geospatial 을 안 타므로 폴리곤은 유효하기만 하면 된다. */
	private void seedRegion(String code, String parentCode) {
		regionRepository.upsert(code, "합성동", parentCode, squareKm2Json(1.0), CELL_AREA_M2);
	}

	/** 격자 블록을 덮는 합성 행정동 — 라벨 붙은 grids 시드가 필요한 케이스용. */
	private void seedBlockRegion(String code, long minGy, long maxGy, long minGx, long maxGx) {
		regionRepository.upsert(code, "합성동", code.substring(0, 5),
			cellBlockPolygonJson(minGy, maxGy, minGx, maxGx), CELL_AREA_M2);
	}

	private void seedStats(long userId, String code, int collected, int total, String rate) {
		em.createNativeQuery("""
			INSERT INTO region_stats (user_id, region_code, collected_count, total_count, progress_rate, updated_at)
			VALUES (:u, :c, :cc, :tc, :pr, :ua)
			""")
			.setParameter("u", userId)
			.setParameter("c", code)
			.setParameter("cc", collected)
			.setParameter("tc", total)
			.setParameter("pr", new BigDecimal(rate))
			.setParameter("ua", LocalDateTime.of(2026, 7, 20, 10, 0, 0))
			.executeUpdate();
	}

	private String occupy(long userId, long gy, long gx) {
		String gridId = GridFixtures.seedLabeledGrid(em, gy, gx);
		GridFixtures.seedUserGrid(em, userId, gridId, 1);
		return gridId;
	}

	private long totalGridCount(String code) {
		Number n = (Number) em.createNativeQuery("SELECT total_grid_count FROM regions WHERE region_code = :c")
			.setParameter("c", code).getSingleResult();
		return n.longValue();
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("수집이 없는 사용자는 분자가 0 이다 (에러 아님)")
	void 수집이_없는_사용자는_분자가_0이다() {
		RegionNationalStatProjection stat = regionRepository.findNationalStat(user1);

		assertThat(stat.getCollectedCount()).isZero();
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("전국 분자는 행정동별 수집 수의 합과 같다")
	void 전국_분자는_행정동별_수집_수의_합과_같다() {
		String a = syntheticCode("1");
		String b = syntheticCode("2");
		String c = syntheticCode("3");
		seedRegion(a, "99900");
		seedRegion(b, "99900");
		seedRegion(c, "99910");
		seedStats(user1, a, 2, 10, "20.00");
		seedStats(user1, b, 3, 10, "30.00");
		seedStats(user1, c, 7, 10, "70.00");

		RegionNationalStatProjection stat = regionRepository.findNationalStat(user1);

		long perRegionSum = regionRepository.findStats(user1, null, false).stream()
			.mapToLong(RegionStatProjection::getCollectedCount)
			.sum();
		assertThat(stat.getCollectedCount()).isEqualTo(12L).isEqualTo(perRegionSum);
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("다른 사용자의 수집은 내 전국 분자에 섞이지 않는다")
	void 다른_사용자의_수집은_내_전국_분자에_섞이지_않는다() {
		String mine = syntheticCode("1");
		String others = syntheticCode("2");
		seedRegion(mine, "99900");
		seedRegion(others, "99900");
		seedStats(user1, mine, 4, 10, "40.00");
		seedStats(user2, others, 9, 10, "90.00");

		assertThat(regionRepository.findNationalStat(user1).getCollectedCount()).isEqualTo(4L);
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("무귀속 격자 점령은 전국 분자에 들어가지 않는다")
	void 무귀속_격자_점령은_전국_분자에_들어가지_않는다() {
		seedBlockRegion(syntheticCode("1"), GY0, GY0 + 3, GX0, GX0 + 3);
		String labeled = occupy(user1, GY0, GX0);
		// 행정동 밖(북쪽 100칸) 공해상 격자 — 중심점을 덮는 행정동이 없어 grids.region_code 가 NULL 이다.
		String unlabeled = occupy(user1, GY0 + 100, GX0);

		regionRepository.refreshRegionStats(user1, labeled);
		regionRepository.refreshRegionStats(user1, unlabeled);

		// 분모가 "행정동 귀속 격자 총수"라 무귀속 점령을 세면 축이 어긋난다(D-4).
		assertThat(regionRepository.findNationalStat(user1).getCollectedCount()).isEqualTo(1L);
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("전국 분모는 모든 행정동의 격자 수 합이다")
	void 전국_분모는_모든_행정동의_격자_수_합이다() {
		long baseline = regionRepository.findNationalStat(user1).getTotalCount();
		String a = syntheticCode("1");
		String b = syntheticCode("2");
		seedRegion(a, "99900");
		seedRegion(b, "99910");

		long after = regionRepository.findNationalStat(user1).getTotalCount();

		assertThat(after - baseline).isEqualTo(totalGridCount(a) + totalGridCount(b));
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("분자가 분모를 넘어도 원값 그대로 반환된다 (서버는 clamp 하지 않는다)")
	void 분자가_분모를_넘어도_원값_그대로_반환된다() {
		long baseline = regionRepository.findNationalStat(user1).getTotalCount();
		String code = syntheticCode("1");
		seedRegion(code, "99900");
		// 면적 근사 분모보다 점령 수가 큰 상황을 강제한다 — 서버는 자르지 않고 상한은 화면 계산 규약이 담당한다(D-3).
		long over = baseline + totalGridCount(code) + 1;
		seedStats(user1, code, (int) over, 10, "0.00");

		RegionNationalStatProjection stat = regionRepository.findNationalStat(user1);

		assertThat(stat.getCollectedCount()).isEqualTo(over);
		assertThat(stat.getCollectedCount()).isGreaterThan(stat.getTotalCount());
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("점령 롤백 직후 전국 분자에 즉시 반영된다")
	void 점령_롤백_직후_전국_분자에_즉시_반영된다() {
		seedBlockRegion(syntheticCode("1"), GY0, GY0 + 3, GX0, GX0 + 3);
		String g1 = occupy(user1, GY0, GX0);
		String g2 = occupy(user1, GY0, GX0 + 1);
		regionRepository.refreshRegionStats(user1, g1);
		assertThat(regionRepository.findNationalStat(user1).getCollectedCount()).isEqualTo(2L);

		em.createNativeQuery("DELETE FROM user_grids WHERE user_id = :u AND grid_id = :g")
			.setParameter("u", user1).setParameter("g", g2).executeUpdate();
		regionRepository.refreshRegionStats(user1, g2);

		assertThat(regionRepository.findNationalStat(user1).getCollectedCount()).isEqualTo(1L);
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("합산 조회는 geospatial 없이 region_stats·regions 스칼라 서브쿼리만 쓴다")
	void 합산_조회는_geospatial_없이_스칼라_서브쿼리만_쓴다() throws Exception {
		Method findNationalStat = RegionRepository.class.getMethod("findNationalStat", long.class);
		String sql = findNationalStat.getAnnotation(Query.class).value();

		assertThat(sql)
			.doesNotContain("ST_")
			.doesNotContain("LEAST")
			.contains("FROM region_stats WHERE user_id = :userId")
			.contains("FROM regions");
	}
}
