package com.msg.fillmap.video.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionView;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * upsertGrid 중심점 라벨 기입 — grids.region_code (MSG-167 §D1, 실 PostGIS · Owner B).
 * VideoRepository.upsertGrid 가 신규 격자 INSERT 시에만 중심점 행정동을 판정해 region_code 에 채우는지,
 * 그 판정이 격자 생애 1회(재방문 무재판정)인지, 저장 라벨이 by-grid 귀속(RegionQueryService.resolveByPoint)과
 * 같은 행정동인지 검증한다. 판정 규칙은 findContainingRegion(93)·refreshRegionStats(155)·V5 백필과 동일하다.
 *
 * 격리(공유 로컬 DB · MEMORY 'shared local DB'): 실존하지 않는 99951 대역 region_code 와 서해 공해상 소형
 * 합성 폴리곤(육상 실데이터 3,558행과 무충돌), 그 폴리곤 안/밖에 중심점이 오도록 잡은 합성 grids +
 * @Transactional 롤백만 쓴다. 오프셋(39600/108500)은 백필 테스트(39500)·155(40000)와 grid_y 로 분리된다.
 * regions/grids 를 truncate 하지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("upsertGrid 중심점 라벨 기입 — grids.region_code (합성 폴리곤·롤백 격리)")
class GridRegionCodeLabelTest {

	// 서해 공해상(lat ≈35.64, lon ≈124.78) 격자 인덱스 — 어떤 실존 행정동에도 안 드는 좌표 대역.
	private static final long GY0 = 17416L;
	private static final long GX0 = 7533L;

	// 실존하지 않는 99951 대역 합성 코드(오름차순: LOW < HIGH — 타이브레이크는 낮은 LOW 를 고른다).
	private static final String REGION_LOW = "9995100001";
	private static final String REGION_HIGH = "9995100002";

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private RegionQueryService regionQueryService;

	@Autowired
	private EntityManager em;

	/** 격자 인덱스 경계 [minGy,maxGy)×[minGx,maxGx) 를 통째로 덮는 합성 행정동을 upsert 한다. */
	private void seedRegion(String code, long minGy, long maxGy, long minGx, long maxGx) {
		String polygon = cellBlockPolygonJson(minGy, maxGy, minGx, maxGx);
		regionRepository.upsert(code, "m167b합성동", code.substring(0, 5), polygon, CELL_AREA_M2);
	}

	/** 프로덕션과 동일 경로로 (gy,gx) 격자를 upsertGrid 등록하고 grid_id 를 돌려준다(라벨 기입 코드 실행). */
	private String registerGrid(long gy, long gx) {
		GridPoint cellCenter = GridFixtures.pointAt(gy + 0.5, gx + 0.5);
		String gridId = GridEncoder.encode(cellCenter.lat(), cellCenter.lon());
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(
			gridId, index.gridY(), index.gridX(), center.lat(), center.lon(), GeoSupport.bboxWkt(gridId));
		return gridId;
	}

	private String regionCodeOf(String gridId) {
		List<?> rows = em.createNativeQuery("SELECT region_code FROM grids WHERE grid_id = :g")
			.setParameter("g", gridId).getResultList();
		return rows.isEmpty() ? null : (String) rows.get(0);
	}

	/** 격자 (gy,gx) 중심점의 by-grid 귀속(resolveByPoint) region_code — 무귀속이면 null. */
	private String byGridLabel(long gy, long gx) {
		GridPoint center = GridFixtures.pointAt(gy + 0.5, gx + 0.5);
		return regionQueryService.resolveByPoint(center.lat(), center.lon())
			.map(RegionView::regionCode).orElse(null);
	}

	@Test
	@DisplayName("첫_업로드로_격자가_등록되면_중심점_행정동_region_code가_기입된다")
	void 첫_업로드로_격자가_등록되면_중심점_행정동_region_code가_기입된다() {
		seedRegion(REGION_HIGH, GY0, GY0 + 3, GX0, GX0 + 3);

		String grid = registerGrid(GY0, GX0);

		assertThat(regionCodeOf(grid)).isEqualTo(REGION_HIGH);
	}

	@Test
	@DisplayName("이미_등록된_격자에_재업로드해도_region_code는_재판정되지_않는다")
	void 이미_등록된_격자에_재업로드해도_region_code는_재판정되지_않는다() {
		// 최초 등록은 HIGH 만 덮어 HIGH 로 라벨된다. 이후 더 낮은 LOW 를 겹쳐 덮으면 재판정 시 LOW 로 뒤집힐
		// 텐데(ORDER BY region_code), WHERE NOT EXISTS 가 재방문을 short-circuit 해 서브쿼리가 안 돌아야 한다.
		seedRegion(REGION_HIGH, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = registerGrid(GY0, GX0);
		seedRegion(REGION_LOW, GY0, GY0 + 3, GX0, GX0 + 3);

		registerGrid(GY0, GX0);   // 재방문 — 같은 격자 재등록

		assertThat(regionCodeOf(grid)).isEqualTo(REGION_HIGH);   // 판정이면 LOW, 가드가 재판정을 막아 HIGH 유지
	}

	@Test
	@DisplayName("다른_사용자가_같은_격자를_점령해도_region_code는_그대로다")
	void 다른_사용자가_같은_격자를_점령해도_region_code는_그대로다() {
		// upsertGrid 는 사용자 무관(격자 등록). 다른 사용자의 첫 업로드도 같은 격자엔 no-op INSERT 라 재판정이 없다.
		seedRegion(REGION_HIGH, GY0, GY0 + 3, GX0, GX0 + 3);
		String grid = registerGrid(GY0, GX0);
		seedRegion(REGION_LOW, GY0, GY0 + 3, GX0, GX0 + 3);

		registerGrid(GY0, GX0);   // 타 사용자 업로드가 태우는 registerGridIfAbsent 와 같은 경로

		assertThat(regionCodeOf(grid)).isEqualTo(REGION_HIGH);
	}

	@Test
	@DisplayName("중심점이_어느_행정동에도_안속하는_해안_격자는_region_code가_null이다")
	void 중심점이_어느_행정동에도_안속하는_해안_격자는_region_code가_null이다() {
		seedRegion(REGION_HIGH, GY0, GY0 + 3, GX0, GX0 + 3);
		// 행정동 밖(북쪽 100칸)·서해 공해상 격자 — 중심점을 덮는 행정동이 없다.
		String coastal = registerGrid(GY0 + 100, GX0);

		assertThat(regionCodeOf(coastal)).isNull();
		assertThat(byGridLabel(GY0 + 100, GX0)).isNull();
	}

	@Test
	@DisplayName("저장된_region_code는_by_grid_귀속과_같은_행정동이다")
	void 저장된_region_code는_by_grid_귀속과_같은_행정동이다() {
		seedRegion(REGION_HIGH, GY0, GY0 + 3, GX0, GX0 + 3);

		String grid = registerGrid(GY0, GX0);

		// 저장 라벨 = resolveByPoint(중심점) 귀속 = 같은 행정동(같은 중심점·같은 규칙).
		assertThat(regionCodeOf(grid)).isEqualTo(REGION_HIGH).isEqualTo(byGridLabel(GY0, GX0));
	}

	@Test
	@DisplayName("경계에_걸친_격자도_중심점_기준_한_행정동으로만_라벨된다")
	void 경계에_걸친_격자도_중심점_기준_한_행정동으로만_라벨된다() {
		// 격자 (GY0,GX0) 의 동서를 가르는 경계선을 셀 내부(중심점 동편, 0.7 지점)에 둔다 — 격자가 서편·동편을 걸친다.
		// 서편은 HIGH, 동편은 LOW. 중심점(0.5 < 0.7)은 서편 HIGH 만 덮으므로 라벨은 HIGH — 낮은 LOW 로 새지 않는다.
		double borderLon = GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.7).lon();
		double minLat = GridFixtures.pointAt(GY0 - 1, GX0 + 0.5).lat();
		double maxLat = GridFixtures.pointAt(GY0 + 2, GX0 + 0.5).lat();
		double westLon = GridFixtures.pointAt(GY0 + 0.5, GX0 - 2).lon();
		double eastLon = GridFixtures.pointAt(GY0 + 0.5, GX0 + 3).lon();
		regionRepository.upsert(REGION_HIGH, "m167b합성동", REGION_HIGH.substring(0, 5),
			rectanglePolygonJson(westLon, minLat, borderLon, maxLat), CELL_AREA_M2);
		regionRepository.upsert(REGION_LOW, "m167b합성동", REGION_LOW.substring(0, 5),
			rectanglePolygonJson(borderLon, minLat, eastLon, maxLat), CELL_AREA_M2);

		String grid = registerGrid(GY0, GX0);

		assertThat(regionCodeOf(grid)).isEqualTo(REGION_HIGH).isEqualTo(byGridLabel(GY0, GX0));
	}
}
