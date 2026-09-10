package com.msg.fillmap.region.service;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.squareKm2Json;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * RegionStatsQueryService 통합 (MSG-156 모듈 2 findStats + MSG-406 findNationalStat, 실 PostGIS).
 * parentCode 실존 검증(§D3)과 빈 리스트·기본 인자 경로, 전국 합산의 뷰 변환을 확인한다.
 * recompute 정합은 155 가, 조회 SQL 세부는 각 모듈 1 이 커버한다.
 * 격리: 신규 유저 + 999 대역 합성 regions/region_stats 직접 시드 + @Transactional 롤백.
 */
@SpringBootTest
@Transactional
@DisplayName("RegionStatsQueryService (합성 row·롤백 격리)")
class RegionStatsQueryServiceTest {

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
		user1 = userRepository.save(User.createLocalUser("stats-svc-q@example.com", "hash", "유저")).getId();
	}

	private void seedRegion(String code, String parentCode) {
		regionRepository.upsert(code, "합성동", parentCode, squareKm2Json(1.0), CELL_AREA_M2);
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

	// 검증: FR-REGION-06
	@Test
	@DisplayName("parentCode 가 없으면 사용자의 모든 수집 행정동을 반환한다")
	void parentCode가_없으면_사용자의_모든_수집_행정동을_반환한다() {
		String a = syntheticCode("1");
		String b = syntheticCode("2");
		seedRegion(a, "99900");
		seedRegion(b, "99910");
		seedStats(user1, a, 2, 10, "20.00");
		seedStats(user1, b, 4, 10, "40.00");

		List<RegionStatView> stats = regionStatsQueryService.findStats(user1, null, true);

		assertThat(stats).extracting(RegionStatView::regionCode).containsExactlyInAnyOrder(a, b);
	}

	@Test
	@DisplayName("실존하지 않는 parentCode 면 REGION_NOT_FOUND 6404 를 던진다")
	void 실존하지_않는_parentCode면_REGION_NOT_FOUND_6404를_던진다() {
		assertThatThrownBy(() -> regionStatsQueryService.findStats(user1, "99999", true))
			.isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).getErrorCode())
			.isEqualTo(RegionErrorCode.REGION_NOT_FOUND);
	}

	// 검증: FR-REGION-06
	@Test
	@DisplayName("유효한 parentCode 인데 수집이 없으면 빈 리스트를 반환한다")
	void 유효한_parentCode인데_수집이_없으면_빈_리스트를_반환한다() {
		String code = syntheticCode("1");
		seedRegion(code, "99900");
		// region_stats 는 시드하지 않는다 — parentCode 는 실존(6404 아님)하나 수집 데이터가 없다.

		List<RegionStatView> stats = regionStatsQueryService.findStats(user1, "99900", true);

		assertThat(stats).isEmpty();
	}

	// 검증: FR-REGION-06
	@Test
	@DisplayName("collectedOnly 기본값은 true 라 0행이 빠진다")
	void collectedOnly_기본값은_true라_0행이_빠진다() {
		String occupied = syntheticCode("1");
		String rolledBack = syntheticCode("2");
		seedRegion(occupied, "99900");
		seedRegion(rolledBack, "99900");
		seedStats(user1, occupied, 3, 10, "30.00");
		seedStats(user1, rolledBack, 0, 10, "0.00");

		List<RegionStatView> stats = regionStatsQueryService.findStats(user1, null, true);

		assertThat(stats).extracting(RegionStatView::regionCode).containsExactly(occupied);
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("전국 조회는 수집이 0 이어도 뷰가 항상 present 다 (Optional 아님)")
	void 전국_조회는_수집이_0이어도_뷰가_항상_present다() {
		RegionNationalStatView view = regionStatsQueryService.findNationalStat(user1);

		assertThat(view).isNotNull();
		assertThat(view.collectedCount()).isZero();
		assertThat(view.totalCount()).isNotNull();
	}

	// 검증: FR-REGION-14
	@Test
	@DisplayName("전국 분자는 내 행정동 수집 수의 합을 원값 그대로 담는다")
	void 전국_분자는_내_행정동_수집_수의_합을_원값_그대로_담는다() {
		String a = syntheticCode("1");
		String b = syntheticCode("2");
		seedRegion(a, "99900");
		seedRegion(b, "99910");
		seedStats(user1, a, 2, 10, "20.00");
		seedStats(user1, b, 5, 10, "50.00");

		assertThat(regionStatsQueryService.findNationalStat(user1).collectedCount()).isEqualTo(7L);
	}
}
