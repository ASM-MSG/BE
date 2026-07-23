package com.msg.fillmap.grid;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;
import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionView;

/**
 * V5 grids.region_code 백필 — 중심점 판정·멱등·by-grid 일치 (MSG-167 §D2, 실 PostGIS).
 * 백필 UPDATE 의 판정 규칙은 RegionRepository.findContainingRegion(93)·refreshRegionStats(155) 와 동일하며
 * (ST_Covers(boundary_geom, center_geom) ORDER BY region_code LIMIT 1), 그 저장 라벨이 RegionQueryService.
 * resolveByPoint(중심점) 의 라이브 판정과 항상 같은 행정동임을 고정한다(§D1 일치 계약).
 *
 * 격리(공유 로컬 DB · MEMORY 'shared local DB'): 실존하지 않는 99950 대역 region_code 와 서해 공해상 소형
 * 합성 폴리곤(육상 실데이터 3,558행과 무충돌), 그 폴리곤 안/밖에 중심점이 오도록 잡은 합성 grids +
 * @Transactional 롤백만 쓴다. 오프셋(39500/108500)은 RegionStatsRecomputeTest(40000/108695)·165(40000/108665)
 * 와 분리된다. regions/grids 를 truncate 하지 않는다. 백필 UPDATE 는 공유 DB 오염을 막으려 grid_y 범위로
 * 스코프한다 — 운영 V5 는 전체 NULL 격자 대상이고, 판정·IS NULL 가드는 바이트 단위로 동일하다.
 */
@SpringBootTest
@Transactional
@DisplayName("V5 grids.region_code 백필 — 중심점 판정·멱등·by-grid 일치 (합성 폴리곤·롤백 격리)")
class GridRegionCodeBackfillTest {

	// 서해 공해상(lat ≈35.55, lon ≈124.78) 격자 인덱스 — 어떤 실존 행정동에도 안 드는 좌표 대역.
	private static final long GY0 = 39500L;
	private static final long GX0 = 108500L;

	// 실존하지 않는 99950 대역 합성 코드(오름차순: A < B — 타이브레이크는 낮은 A 를 고른다).
	private static final String REGION_A = "9995000001";
	private static final String REGION_B = "9995000002";

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private RegionQueryService regionQueryService;

	@Autowired
	private EntityManager em;

	/** 격자 인덱스 경계 [minGy,maxGy)×[minGx,maxGx) 를 통째로 덮는 합성 행정동을 upsert 한다. */
	private void seedRegion(String code, long minGy, long maxGy, long minGx, long maxGx) {
		String polygon = rectanglePolygonJson(
			minGx * GRID_LNG_STEP, minGy * GRID_LAT_STEP,
			maxGx * GRID_LNG_STEP, maxGy * GRID_LAT_STEP);
		regionRepository.upsert(code, "m167합성동", code.substring(0, 5), polygon, CELL_AREA_M2);
	}

	/**
	 * V5 백필과 동일한 판정(ST_Covers(center_geom) ORDER BY region_code LIMIT 1)·IS NULL 가드의 UPDATE.
	 * grid_y 범위는 공유 DB 격리용 스코프(운영 V5 는 스코프 없이 전체 NULL 격자를 채운다). 갱신 행 수 반환.
	 */
	private int backfill(long gyLo, long gyHi) {
		return em.createNativeQuery("""
			UPDATE grids g SET region_code = (
				SELECT r.region_code FROM regions r
				WHERE ST_Covers(r.boundary_geom, g.center_geom)
				ORDER BY r.region_code
				LIMIT 1)
			WHERE g.region_code IS NULL
			  AND g.grid_y >= :gyLo AND g.grid_y < :gyHi
			""")
			.setParameter("gyLo", gyLo)
			.setParameter("gyHi", gyHi)
			.executeUpdate();
	}

	private String regionCodeOf(String gridId) {
		List<?> rows = em.createNativeQuery("SELECT region_code FROM grids WHERE grid_id = :g")
			.setParameter("g", gridId).getResultList();
		return rows.isEmpty() ? null : (String) rows.get(0);
	}

	/** 격자 (gy,gx) 중심점의 by-grid 귀속(resolveByPoint) region_code — 무귀속이면 null. */
	private String byGridLabel(long gy, long gx) {
		return regionQueryService.resolveByPoint((gy + 0.5) * GRID_LAT_STEP, (gx + 0.5) * GRID_LNG_STEP)
			.map(RegionView::regionCode).orElse(null);
	}

	@Test
	@DisplayName("백필 라벨은 격자 중심점 행정동이고 그 격자 by-grid 귀속과 같은 행정동이다")
	void 백필_라벨은_격자_중심점_행정동이고_by_grid_귀속과_같다() {
		seedRegion(REGION_A, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = GridFixtures.seedGrid(em, GY0, GX0);

		backfill(GY0, GY0 + 3);

		assertThat(regionCodeOf(grid)).isEqualTo(REGION_A).isEqualTo(byGridLabel(GY0, GX0));
	}

	@Test
	@DisplayName("경계에 인접한 두 행정동에서도 중심점 기준 한 행정동으로 라벨되고 by-grid 와 일치한다")
	void 경계에_인접한_두_행정동에서도_중심점_기준_한_행정동으로_라벨되고_by_grid와_일치한다() {
		// 격자 (GY0,GX0) 의 동서를 가르는 경계선을 셀 내부(중심점 동편, 0.7 지점)에 둔다 — 격자가 A|B 를 물리적으로 걸친다.
		double borderLon = (GX0 + 0.7) * GRID_LNG_STEP;
		double minLat = (GY0 - 1) * GRID_LAT_STEP;
		double maxLat = (GY0 + 2) * GRID_LAT_STEP;
		regionRepository.upsert(REGION_A, "m167합성동", REGION_A.substring(0, 5),
			rectanglePolygonJson((GX0 - 2) * GRID_LNG_STEP, minLat, borderLon, maxLat), CELL_AREA_M2);
		regionRepository.upsert(REGION_B, "m167합성동", REGION_B.substring(0, 5),
			rectanglePolygonJson(borderLon, minLat, (GX0 + 3) * GRID_LNG_STEP, maxLat), CELL_AREA_M2);
		String grid = GridFixtures.seedGrid(em, GY0, GX0);

		backfill(GY0, GY0 + 1);

		// 중심점(0.5 < 0.7)은 서편 A 안 → 백필·by-grid 둘 다 A, 이중 귀속 없음.
		assertThat(regionCodeOf(grid)).isEqualTo(REGION_A).isEqualTo(byGridLabel(GY0, GX0));
	}

	@Test
	@DisplayName("중심점을 두 행정동이 함께 덮어도 백필과 by-grid 가 같은 타이브레이크(region_code 오름차순)로 귀속된다")
	void 중심점을_두_행정동이_함께_덮어도_백필과_by_grid가_같은_타이브레이크로_귀속된다() {
		// 동일한 폴리곤을 두 행정동으로 등록 — 격자 중심점을 둘 다 덮는 다중매칭을 강제한다.
		seedRegion(REGION_A, GY0, GY0 + 3, GX0, GX0 + 3);
		seedRegion(REGION_B, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = GridFixtures.seedGrid(em, GY0, GX0);

		backfill(GY0, GY0 + 3);

		// ORDER BY region_code LIMIT 1 → 낮은 코드 A. 백필·by-grid 가 같은 규칙이라 둘 다 A 로 결정적이다.
		assertThat(regionCodeOf(grid)).isEqualTo(REGION_A).isEqualTo(byGridLabel(GY0, GX0));
	}

	@Test
	@DisplayName("백필을 두 번 실행해도 같은 region_code 로 수렴한다 (IS NULL 가드 멱등)")
	void 백필을_두번_실행해도_같은_region_code로_수렴한다() {
		seedRegion(REGION_A, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = GridFixtures.seedGrid(em, GY0, GX0);

		int first = backfill(GY0, GY0 + 3);
		String afterFirst = regionCodeOf(grid);
		int second = backfill(GY0, GY0 + 3);

		assertThat(first).isEqualTo(1);                          // 첫 실행은 NULL 격자 1건을 채운다
		assertThat(second).isZero();                             // 재실행은 IS NULL 가드로 0건 — 라벨 변화 없음
		assertThat(regionCodeOf(grid)).isEqualTo(afterFirst).isEqualTo(REGION_A);
	}

	@Test
	@DisplayName("백필은 이미 라벨된 격자를 재판정하지 않는다 (region_code IS NULL 행만 채운다)")
	void 백필은_이미_라벨된_격자를_재판정하지_않는다() {
		// A·B 둘 다 격자를 덮게 등록하면 판정(ORDER BY)은 낮은 A 를 고른다. 그런데 미리 B 로 라벨해 두면
		// IS NULL 가드가 그 격자를 건너뛰어야 A 로 뒤집히지 않는다.
		seedRegion(REGION_A, GY0, GY0 + 3, GX0, GX0 + 3);
		seedRegion(REGION_B, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = GridFixtures.seedGrid(em, GY0, GX0);
		em.createNativeQuery("UPDATE grids SET region_code = :c WHERE grid_id = :g")
			.setParameter("c", REGION_B).setParameter("g", grid).executeUpdate();

		int updated = backfill(GY0, GY0 + 3);

		assertThat(updated).isZero();
		assertThat(regionCodeOf(grid)).isEqualTo(REGION_B);      // 판정이면 A 지만, 가드가 재판정을 막아 B 유지
	}

	@Test
	@DisplayName("중심점이 어느 행정동에도 안 속하는 해안 격자는 백필 후에도 region_code 가 null 이다")
	void 중심점이_어느_행정동에도_안속하는_해안_격자는_백필_후에도_region_code가_null이다() {
		seedRegion(REGION_A, GY0, GY0 + 3, GX0, GX0 + 3);
		// 행정동 밖(북쪽 100칸)·서해 공해상 격자 — 중심점을 덮는 행정동이 없다.
		long coastalGy = GY0 + 100;
		String coastal = GridFixtures.seedGrid(em, coastalGy, GX0);

		backfill(coastalGy, coastalGy + 1);

		// 백필·by-grid 모두 무귀속(null/empty) 으로 일치한다.
		assertThat(regionCodeOf(coastal)).isNull();
		assertThat(byGridLabel(coastalGy, GX0)).isNull();
	}
}
