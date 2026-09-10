package com.msg.fillmap.region.service;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.squareKm2Json;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * RegionStatsQueryService.findDistricts 통합 (MSG-435 모듈 2, 실 PostGIS). 뷰 변환과, 반환한 식별자가
 * 기존 수집률 필터에 그대로 통하는지(FR-4)를 본다. 정렬·합산·0 제외 같은 SQL 세부는 모듈 1 이 커버한다.
 * 격리: 신규 유저 + 이 테스트 전용 parent_code 9995x 합성 행정동 + @Transactional 롤백.
 */
@SpringBootTest
@Transactional
@DisplayName("RegionStatsQueryService.findDistricts (합성 row·롤백 격리)")
class RegionDistrictServiceTest {

	private static final String PARENT_CODE = "99950";
	private static final String REGION_CODE = "9995000001";

	@Autowired
	private RegionStatsQueryService regionStatsQueryService;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long user1;

	@BeforeEach
	void setUp() {
		user1 = userRepository.save(User.createLocalUser("district-svc@example.com", "hash", "유저")).getId();
		regionRepository.upsert(REGION_CODE, "합성시 합성구 합성동", PARENT_CODE, squareKm2Json(1.0), CELL_AREA_M2);
	}

	private RegionDistrictView mySynthetic(List<RegionDistrictView> districts) {
		return districts.stream()
			.filter(d -> PARENT_CODE.equals(d.parentCode()))
			.findFirst()
			.orElseThrow();
	}

	// 검증: FR-REGION-15
	@Test
	@DisplayName("시군구 뷰에 식별자·이름·격자 수가 담긴다")
	void 시군구_뷰에_식별자_이름_격자_수가_담긴다() {
		RegionDistrictView district = mySynthetic(regionStatsQueryService.findDistricts());

		assertThat(district.name()).isEqualTo("합성구");
		assertThat(district.gridCount()).isPositive();
	}

	// 검증: FR-REGION-15
	@Test
	@DisplayName("응답 식별자로 기존 수집률 시군구 필터를 그대로 쓸 수 있다")
	void 응답_식별자로_기존_수집률_시군구_필터를_그대로_쓸_수_있다() {
		em.createNativeQuery("""
			INSERT INTO region_stats (user_id, region_code, collected_count, total_count, progress_rate, updated_at)
			VALUES (:u, :c, 3, 10, :pr, :ua)
			""")
			.setParameter("u", user1)
			.setParameter("c", REGION_CODE)
			.setParameter("pr", new BigDecimal("30.00"))
			.setParameter("ua", LocalDateTime.of(2026, 8, 19, 10, 0, 0))
			.executeUpdate();
		String parentCode = mySynthetic(regionStatsQueryService.findDistricts()).parentCode();

		// 실존하지 않는 코드면 6404 가 나는 자리다 — 목록이 준 식별자는 그 검증을 그대로 통과해야 이어 쓸 수 있다.
		List<RegionStatView> stats = regionStatsQueryService.findStats(user1, parentCode, true);

		assertThat(stats).extracting(RegionStatView::regionCode).containsExactly(REGION_CODE);
	}
}
