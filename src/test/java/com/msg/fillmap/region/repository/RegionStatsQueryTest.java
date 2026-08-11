package com.msg.fillmap.region.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.squareKm2Json;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * RegionRepository.findStats — 행정동별 수집률 조회 native (MSG-156 모듈 1, 실 PostGIS).
 * 격리: 신규 유저 + 999 대역 합성 regions row(RegionRepository.upsert) + region_stats 합성 row 직접 시드
 * (155 write 경로에 의존하지 않음) + @Transactional 롤백. regions/region_stats 를 truncate 하지 않는다(공유 DB).
 */
@SpringBootTest
@Transactional
@DisplayName("RegionRepository.findStats — 행정동별 수집률 조회 (합성 row·롤백 격리)")
class RegionStatsQueryTest {

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
		user1 = userRepository.save(User.createLocalUser("stats-q1@example.com", "hash", "유저1")).getId();
		user2 = userRepository.save(User.createLocalUser("stats-q2@example.com", "hash", "유저2")).getId();
	}

	/** 999 대역 합성 행정동을 upsert 한다 — 조회는 geospatial 을 안 타므로 폴리곤은 유효하기만 하면 된다. */
	private void seedRegion(String code, String parentCode, String name) {
		regionRepository.upsert(code, name, parentCode, squareKm2Json(1.0), CELL_AREA_M2);
	}

	/** region_stats 합성 row 직접 시드(155 write 경로 미사용). */
	private void seedStats(long userId, String code, int collected, int total, String rate, LocalDateTime updatedAt) {
		em.createNativeQuery("""
			INSERT INTO region_stats (user_id, region_code, collected_count, total_count, progress_rate, updated_at)
			VALUES (:u, :c, :cc, :tc, :pr, :ua)
			""")
			.setParameter("u", userId)
			.setParameter("c", code)
			.setParameter("cc", collected)
			.setParameter("tc", total)
			.setParameter("pr", new BigDecimal(rate))
			.setParameter("ua", updatedAt)
			.executeUpdate();
	}

	private void seedStats(long userId, String code, int collected, int total, String rate) {
		seedStats(userId, code, collected, total, rate, LocalDateTime.of(2026, 7, 20, 10, 0, 0));
	}

	// 검증: FR-REGION-06
	@Test
	@DisplayName("내 region_stats 를 regions 와 조인해 regionName 과 parentCode 가 채워진다")
	void 내_region_stats를_regions와_조인해_regionName과_parentCode가_채워진다() {
		String code = syntheticCode("1");
		seedRegion(code, "99900", "합성특별시 합성구 역삼합성동");
		seedStats(user1, code, 5, 20, "25.00");

		List<RegionStatProjection> stats = regionRepository.findStats(user1, null, true);

		assertThat(stats).hasSize(1);
		RegionStatProjection row = stats.get(0);
		assertThat(row.getRegionCode()).isEqualTo(code);
		assertThat(row.getRegionName()).isEqualTo("합성특별시 합성구 역삼합성동");
		assertThat(row.getParentCode()).isEqualTo("99900");
		assertThat(row.getCollectedCount()).isEqualTo(5);
		assertThat(row.getTotalCount()).isEqualTo(20);
	}

	// 검증: FR-REGION-06
	@Test
	@DisplayName("parentCode 로 필터하면 그 시군구 산하 행정동 stats 만 반환된다")
	void parentCode로_필터하면_그_시군구_산하_행정동_stats만_반환된다() {
		String inParent = syntheticCode("1");
		String otherParent = syntheticCode("2");
		seedRegion(inParent, "99900", "합성구A 동");
		seedRegion(otherParent, "99910", "합성구B 동");
		seedStats(user1, inParent, 3, 10, "30.00");
		seedStats(user1, otherParent, 4, 10, "40.00");

		List<RegionStatProjection> stats = regionRepository.findStats(user1, "99900", true);

		assertThat(stats).hasSize(1);
		assertThat(stats.get(0).getRegionCode()).isEqualTo(inParent);
	}

	// 검증: FR-REGION-06
	@Test
	@DisplayName("collectedOnly true 면 collected_count 가 0 인 롤백행은 제외된다")
	void collectedOnly_true면_collected_count가_0인_롤백행은_제외된다() {
		String occupied = syntheticCode("1");
		String rolledBack = syntheticCode("2");
		seedRegion(occupied, "99900", "수집동");
		seedRegion(rolledBack, "99900", "롤백동");
		seedStats(user1, occupied, 2, 10, "20.00");
		seedStats(user1, rolledBack, 0, 10, "0.00");

		List<RegionStatProjection> stats = regionRepository.findStats(user1, null, true);

		assertThat(stats).hasSize(1);
		assertThat(stats.get(0).getRegionCode()).isEqualTo(occupied);
	}

	// 검증: FR-REGION-06
	@Test
	@DisplayName("collectedOnly false 면 collected_count 가 0 인 손댄행도 포함된다")
	void collectedOnly_false면_collected_count가_0인_손댄행도_포함된다() {
		String occupied = syntheticCode("1");
		String rolledBack = syntheticCode("2");
		seedRegion(occupied, "99900", "수집동");
		seedRegion(rolledBack, "99900", "롤백동");
		seedStats(user1, occupied, 2, 10, "20.00");
		seedStats(user1, rolledBack, 0, 10, "0.00");

		List<RegionStatProjection> stats = regionRepository.findStats(user1, null, false);

		assertThat(stats).extracting(RegionStatProjection::getRegionCode)
			.containsExactlyInAnyOrder(occupied, rolledBack);
	}

	// 검증: FR-REGION-06
	@Test
	@DisplayName("수집한 행정동이 없으면 빈 리스트를 반환한다")
	void 수집한_행정동이_없으면_빈_리스트를_반환한다() {
		List<RegionStatProjection> stats = regionRepository.findStats(user1, null, true);

		assertThat(stats).isEmpty();
	}

	// 검증: FR-REGION-04
	@Test
	@DisplayName("progress_rate 가 100 을 넘어도 100 으로 clamp 되어 반환된다")
	void progress_rate가_100을_넘어도_100으로_clamp되어_반환된다() {
		String code = syntheticCode("1");
		seedRegion(code, "99900", "면적근사로_초과한_동");
		seedStats(user1, code, 12, 10, "103.50");

		List<RegionStatProjection> stats = regionRepository.findStats(user1, null, true);

		assertThat(stats.get(0).getProgressRate()).isEqualByComparingTo("100.00");
	}

	// 검증: FR-REGION-06
	@Test
	@DisplayName("다른 사용자의 region_stats 는 섞이지 않는다")
	void 다른_사용자의_region_stats는_섞이지_않는다() {
		String mine = syntheticCode("1");
		String others = syntheticCode("2");
		seedRegion(mine, "99900", "내동");
		seedRegion(others, "99900", "남의동");
		seedStats(user1, mine, 1, 10, "10.00");
		seedStats(user2, others, 9, 10, "90.00");

		List<RegionStatProjection> stats = regionRepository.findStats(user1, null, true);

		assertThat(stats).hasSize(1);
		assertThat(stats.get(0).getRegionCode()).isEqualTo(mine);
	}

	@Test
	@DisplayName("updatedAt 은 region_stats 의 updated_at 과 같다")
	void updatedAt은_region_stats의_updated_at과_같다() {
		String code = syntheticCode("1");
		LocalDateTime cachedAt = LocalDateTime.of(2026, 7, 19, 8, 15, 42);
		seedRegion(code, "99900", "캐시동");
		seedStats(user1, code, 3, 10, "30.00", cachedAt);

		List<RegionStatProjection> stats = regionRepository.findStats(user1, null, true);

		assertThat(stats.get(0).getUpdatedAt()).isEqualTo(cachedAt);
	}

	// 검증: FR-REGION-12
	@Test
	@DisplayName("조회는 geospatial 없이 region_stats 와 regions equi-join 만 한다")
	void 조회는_geospatial_없이_region_stats와_regions_equi_join만_한다() throws Exception {
		Method findStats = RegionRepository.class.getMethod("findStats", long.class, String.class, boolean.class);
		String sql = findStats.getAnnotation(Query.class).value();

		assertThat(sql)
			.doesNotContain("ST_")
			.contains("JOIN regions r ON r.region_code = rs.region_code");
	}
}
