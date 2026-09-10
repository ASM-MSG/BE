package com.msg.fillmap.region.service;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * RegionStatsCommandService.refresh 통합 (MSG-155 모듈 2, 실 PostGIS). recompute 정합은 모듈 1 이 커버하므로
 * 여기서는 계약 경로(리포지토리 위임)와 해안 no-op 만 확인한다. 격리는 999 대역 합성 폴리곤 + @Transactional 롤백.
 */
@SpringBootTest
@Transactional
@DisplayName("RegionStatsCommandService.refresh (합성 폴리곤·롤백 격리)")
class RegionStatsCommandServiceTest {

	private static final long GY0 = 17810L;
	private static final long GX0 = 7746L;

	@Autowired
	private RegionStatsCommandService regionStatsCommandService;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long user1;

	@BeforeEach
	void setUp() {
		user1 = userRepository.save(User.createLocalUser("stats-svc@example.com", "hash", "유저")).getId();
	}

	private void seedRegion(String code) {
		String polygon = cellBlockPolygonJson(GY0, GY0 + 3, GX0, GX0 + 3);
		regionRepository.upsert(code, "합성동", code.substring(0, 5), polygon, CELL_AREA_M2);
	}

	private long statsRowCount(long userId) {
		Number n = (Number) em.createNativeQuery(
				"SELECT COUNT(*) FROM region_stats WHERE user_id = :u")
			.setParameter("u", userId).getSingleResult();
		return n.longValue();
	}

	@Test
	@DisplayName("해안 격자 gridId 로 refresh 하면 아무것도 바꾸지 않는다 (no-op)")
	void 해안_격자_gridId로_refresh하면_아무것도_바꾸지_않는다() {
		seedRegion(syntheticCode("1"));
		// 행정동 밖(공해상) 격자 — 중심점을 덮는 행정동이 없어 seedLabeledGrid 라벨도 NULL(equi 가드로 no-op).
		String coastal = GridFixtures.seedLabeledGrid(em, GY0 + 100, GX0);
		GridFixtures.seedUserGrid(em, user1, coastal, 1);

		regionStatsCommandService.refresh(user1, coastal);

		assertThat(statsRowCount(user1)).isZero();
	}

	// 검증: FR-REGION-05
	@Test
	@DisplayName("refresh 는 격자 중심 행정동의 region_stats 를 recompute 한다")
	void refresh는_격자_중심_행정동의_region_stats를_recompute한다() {
		String a = syntheticCode("1");
		seedRegion(a);
		// region 을 먼저 시드했으므로 seedLabeledGrid 가 grids.region_code 를 A 로 라벨 → equi refresh 가 이를 읽는다.
		String grid = GridFixtures.seedLabeledGrid(em, GY0, GX0);
		GridFixtures.seedUserGrid(em, user1, grid, 1);

		regionStatsCommandService.refresh(user1, grid);

		Number collected = (Number) em.createNativeQuery(
				"SELECT collected_count FROM region_stats WHERE user_id = :u AND region_code = :c")
			.setParameter("u", user1).setParameter("c", a).getSingleResult();
		assertThat(collected.intValue()).isEqualTo(1);
	}
}
