package com.msg.fillmap.region.service;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;
import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * RegionStatsQueryService 단건 탐험률 통합 (MSG-153 모듈 3·4, 실 PostGIS). by-point/by-grid 가 resolveByPoint 로
 * 행정동을 판정하고 findStatByRegion 으로 수집률을 내는 경로 — 0% 합성·no-match·6400·clamp·중심점 축 일치(155)를 검증한다.
 * 격리: 신규 유저(m153a) + 실존하지 않는 99930~99934 대역 region_code + 서해 공해상 소형 합성 폴리곤 + region_stats 직접 시드 +
 * @Transactional 롤백. regions/grids/user_grids/region_stats 를 truncate 하지 않는다(공유 DB). 실데이터 regions(3,558행) 불가침.
 */
@SpringBootTest
@Transactional
@DisplayName("RegionStatsQueryService by-point·by-grid (합성 폴리곤·롤백 격리)")
class RegionStatsPointGridQueryServiceTest {

	// 실존하지 않는 sido 99930 대역 합성 행정동(타 트랙 9992x·9994x 와 겹치지 않는 대역).
	private static final String REGION_A = "9993000001";

	// 서해 공해상(lat ≈36.09, lon ≈125.12) 격자 인덱스 — 육상 실데이터·타 트랙과 겹치지 않는 좌표 대역.
	private static final long GY = 40100L;
	private static final long GX = 108800L;

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
		user1 = userRepository.save(User.createLocalUser("m153a-stat@example.com", "hash", "m153a탐험")).getId();
	}

	/** 격자 인덱스 경계 [minGy,maxGy)×[minGx,maxGx) 를 덮는 합성 행정동을 upsert 한다(parent = code 앞 5자리). */
	private void seedRegion(String code, long minGy, long maxGy, long minGx, long maxGx) {
		String polygon = rectanglePolygonJson(
			minGx * GRID_LNG_STEP, minGy * GRID_LAT_STEP,
			maxGx * GRID_LNG_STEP, maxGy * GRID_LAT_STEP);
		regionRepository.upsert(code, "합성동", code.substring(0, 5), polygon, CELL_AREA_M2);
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

	private int totalGridCount(String code) {
		Number n = (Number) em.createNativeQuery("SELECT total_grid_count FROM regions WHERE region_code = :c")
			.setParameter("c", code).getSingleResult();
		return n.intValue();
	}

	private double centerLat() {
		return (GY + 0.5) * GRID_LAT_STEP;
	}

	private double centerLon() {
		return (GX + 0.5) * GRID_LNG_STEP;
	}

	@Test
	@DisplayName("현재 위치 행정동의 수집률을 반환한다")
	void 현재위치_행정동의_수집률을_반환한다() {
		seedRegion(REGION_A, GY, GY + 3, GX, GX + 3);
		seedStats(user1, REGION_A, 5, 20, "25.00");

		Optional<RegionStatView> stat = regionStatsQueryService.findStatByPoint(user1, centerLat(), centerLon());

		assertThat(stat).isPresent();
		assertThat(stat.get().regionCode()).isEqualTo(REGION_A);
		assertThat(stat.get().collectedCount()).isEqualTo(5);
		assertThat(stat.get().totalCount()).isEqualTo(20);
		assertThat(stat.get().progressRate()).isEqualByComparingTo("25.00");
	}

	@Test
	@DisplayName("수집이 없는 행정동은 0퍼센트로 합성해 반환한다")
	void 수집이_없는_행정동은_0퍼센트로_합성해_반환한다() {
		seedRegion(REGION_A, GY, GY + 3, GX, GX + 3);
		// region_stats 를 시드하지 않는다 — 내가 선 행정동에 아직 수집이 없는 정상 0% 초기값(§D6).

		Optional<RegionStatView> stat = regionStatsQueryService.findStatByPoint(user1, centerLat(), centerLon());

		assertThat(stat).isPresent();
		assertThat(stat.get().regionCode()).isEqualTo(REGION_A);
		assertThat(stat.get().collectedCount()).isZero();
		assertThat(stat.get().totalCount()).isEqualTo(totalGridCount(REGION_A));
		assertThat(stat.get().progressRate()).isEqualByComparingTo("0.00");
		assertThat(stat.get().updatedAt()).isNull();
	}

	@Test
	@DisplayName("행정동에 속하지 않는 좌표는 200과 null이다")
	void 행정동에_속하지_않는_좌표는_200과_null이다() {
		// 어떤 행정동도 시드하지 않은 서해 공해상 좌표(실데이터 미포함, 서비스 범위 내) → no-match.
		Optional<RegionStatView> stat = regionStatsQueryService.findStatByPoint(user1, 36.5, 125.5);

		assertThat(stat).isEmpty();
	}

	@Test
	@DisplayName("서비스범위 밖 좌표는 6400이다")
	void 서비스범위_밖_좌표는_6400이다() {
		assertThatThrownBy(() -> regionStatsQueryService.findStatByPoint(user1, 10.0, 100.0))
			.isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).getErrorCode())
			.isEqualTo(RegionErrorCode.INVALID_COORDINATE);
	}

	@Test
	@DisplayName("progress_rate가 100을 넘어도 100으로 clamp되어 반환된다")
	void progress_rate가_100을_넘어도_100으로_clamp되어_반환된다() {
		seedRegion(REGION_A, GY, GY + 3, GX, GX + 3);
		seedStats(user1, REGION_A, 30, 20, "150.00");

		Optional<RegionStatView> stat = regionStatsQueryService.findStatByPoint(user1, centerLat(), centerLon());

		assertThat(stat).isPresent();
		assertThat(stat.get().progressRate()).isEqualByComparingTo("100.00");
	}

	@Test
	@DisplayName("격자 중심점이 속한 행정동의 수집률을 반환한다")
	void 격자_중심점이_속한_행정동의_수집률을_반환한다() {
		seedRegion(REGION_A, GY, GY + 3, GX, GX + 3);
		seedStats(user1, REGION_A, 3, 20, "15.00");

		Optional<RegionStatView> stat = regionStatsQueryService.findStatByGrid(user1, GridFixtures.gridId(GY, GX));

		assertThat(stat).isPresent();
		assertThat(stat.get().regionCode()).isEqualTo(REGION_A);
		assertThat(stat.get().collectedCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("중심점 귀속은 155 refreshRegionStats와 같은 행정동을 고른다")
	void 중심점_귀속은_155_refreshRegionStats와_같은_행정동을_고른다() {
		seedRegion(REGION_A, GY, GY + 3, GX, GX + 3);
		// 155 경로로 점령 → refreshRegionStats 가 중심점 행정동(REGION_A)에 region_stats(collected=1)를 쓴다.
		String gridId = GridFixtures.seedGrid(em, GY, GX);
		GridFixtures.seedUserGrid(em, user1, gridId, 1);
		regionRepository.refreshRegionStats(user1, gridId);

		Optional<RegionStatView> stat = regionStatsQueryService.findStatByGrid(user1, gridId);

		// by-grid 의 중심점 귀속이 155 와 같은 행정동을 골라, 155 가 쓴 collected_count 를 그대로 읽는다(축 일치).
		assertThat(stat).isPresent();
		assertThat(stat.get().regionCode()).isEqualTo(REGION_A);
		assertThat(stat.get().collectedCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("중심점이 어떤 행정동에도 안 속하면 200과 null이다")
	void 중심점이_어떤_행정동에도_안_속하면_200과_null이다() {
		// REGION_A 를 시드하지 않은 서해 공해상 격자 → 중심점 무귀속.
		Optional<RegionStatView> stat = regionStatsQueryService.findStatByGrid(user1, GridFixtures.gridId(GY, GX));

		assertThat(stat).isEmpty();
	}

	@Test
	@DisplayName("형식이 잘못된 gridId는 200과 null이다")
	void 형식이_잘못된_gridId는_200과_null이다() {
		assertThat(regionStatsQueryService.findStatByGrid(user1, "abc")).isEmpty();
		assertThat(regionStatsQueryService.findStatByGrid(user1, "12_ab")).isEmpty();
		assertThat(regionStatsQueryService.findStatByGrid(user1, "")).isEmpty();
		assertThat(regionStatsQueryService.findStatByGrid(user1, null)).isEmpty();
	}
}
