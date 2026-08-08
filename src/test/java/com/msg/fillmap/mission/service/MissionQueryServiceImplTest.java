package com.msg.fillmap.mission.service;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridConstants;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.dto.MissionShape.BoxShape;
import com.msg.fillmap.mission.dto.MissionShape.Cell;
import com.msg.fillmap.mission.dto.MissionShape.CellsShape;
import com.msg.fillmap.mission.dto.MissionShape.LatLng;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.dto.MissionShape.RegionShape;
import com.msg.fillmap.mission.dto.MissionShape.Spot;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;
import com.msg.fillmap.region.repository.RegionRepository;

/**
 * 유형별 shape 합성 검증 (MissionQueryServiceImpl, 실 PostGIS · MSG-222 §도메인 3 · Owner B). 활성 미션을 유형에
 * 맞는 단일 shape 로 합성하는지 — COURSE→PATH, EVENT→BOX, THEME/CONTINUOUS→CELLS, AREA→REGION — 와 좌표가
 * GridEncoder 산출과 일치하는지 검증한다. 캐시 만료는 MissionQueryServiceCacheTest, HTTP 는 컨트롤러 테스트 담당.
 *
 * 격리(공유 로컬 DB): missions 는 시드가 없어 @Transactional 롤백으로 충분. 각 테스트는 새 서비스 인스턴스를 만들어
 * 캐시를 비운 채 이 tx 에서 삽입한 미션(무기간 → 항상 활성)만 조회하고, 그 미션 id 로 DTO 를 스코프한다.
 */
@SpringBootTest
@Transactional
@DisplayName("MissionQueryServiceImpl 유형별 shape 합성 (실 PostGIS)")
class MissionQueryServiceImplTest {

	private static final long GY0 = 17618L;
	private static final long GX0 = 7861L;

	/** PROJ(PostGIS)와 Proj4J 의 수치 오차 허용치 — 1e-9 도는 위도 약 0.1mm 로 셀(100m) 판정에 무해하다. */
	private static final double TOLERANCE_DEG = 1e-9;

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	/** 캐시를 비운 새 서비스 인스턴스 — 테스트마다 방금 삽입한 미션만 재계산해 조회하게 한다. */
	private MissionQueryService newService() {
		return new MissionQueryServiceImpl(
			missionRepository, missionGridRepository, Clock.systemDefaultZone(), Duration.ofHours(1).toMillis());
	}

	/**
	 * BOX 폴리곤을 PostGIS 오라클과 대조한다. 기대값을 자바로 다시 계산하면 프로덕션과 같은 산술을 두 번 쓰는
	 * 동어반복이 되므로, 셀 경계 생성부터 감싸기까지 전부 DB 에 맡긴다 — PROJ(ST_Transform)로 5179 셀
	 * 사각형을 4326 으로 되돌리고 ST_Collect·ST_Envelope 로 축정렬 최소 사각형을 얻는다. 좌표계 정의는 앱과
	 * 같은 계약 문자열이라 남는 차이는 PROJ 와 Proj4J 의 수치 오차뿐이다(서브밀리미터 = 1e-9 도 미만).
	 * 링 순서(남서→남동→북동→북서→남서)는 스펙이 정한 계약이라 여기서 직접 못 박는다 (MSG-222 §도메인 3).
	 */
	private void assertBoxEnvelope(BoxShape shape, String... gridIds) {
		Object[] bounds = (Object[]) em.createNativeQuery("""
			SELECT ST_XMin(envelope), ST_YMin(envelope), ST_XMax(envelope), ST_YMax(envelope)
			FROM (
				SELECT ST_Envelope(ST_Collect(ST_Transform(
					ST_MakeEnvelope(cell.gx * 100, cell.gy * 100, (cell.gx + 1) * 100, (cell.gy + 1) * 100),
					:def5179, :defWgs84))) AS envelope
				FROM unnest(string_to_array(:gridIds, ',')) AS t(grid_id),
					LATERAL (SELECT split_part(t.grid_id, '_', 1)::bigint AS gy,
						split_part(t.grid_id, '_', 2)::bigint AS gx) cell
			) e
			""")
			.setParameter("def5179", GridConstants.CRS_DEF_EPSG5179)
			.setParameter("defWgs84", "+proj=longlat +datum=WGS84 +no_defs")
			.setParameter("gridIds", String.join(",", gridIds))
			.getSingleResult();
		double minLon = ((Number) bounds[0]).doubleValue();
		double minLat = ((Number) bounds[1]).doubleValue();
		double maxLon = ((Number) bounds[2]).doubleValue();
		double maxLat = ((Number) bounds[3]).doubleValue();
		List<LatLng> expected = List.of(
			new LatLng(minLat, minLon),
			new LatLng(minLat, maxLon),
			new LatLng(maxLat, maxLon),
			new LatLng(maxLat, minLon),
			new LatLng(minLat, minLon));

		assertThat(shape.polygon()).hasSize(5);
		for (int i = 0; i < expected.size(); i++) {
			assertThat(shape.polygon().get(i).lat()).isCloseTo(expected.get(i).lat(), offset(TOLERANCE_DEG));
			assertThat(shape.polygon().get(i).lng()).isCloseTo(expected.get(i).lng(), offset(TOLERANCE_DEG));
		}
	}

	/** 중심 ±radius 격자 전부 (시더 산출물 형태 — POPUP 은 81셀). */
	private static List<String> allCells(long radius) {
		List<String> cells = new ArrayList<>();
		for (long dy = -radius; dy <= radius; dy++) {
			for (long dx = -radius; dx <= radius; dx++) {
				cells.add(gid(GY0 + dy, GX0 + dx));
			}
		}
		return cells;
	}

	private static String gid(long gridY, long gridX) {
		return gridY + "_" + gridX;
	}

	/** 무기간(항상 활성) 미션 한 건 삽입 후 id 반환. region/path 는 CAST 로 null 타입을 명시한다. */
	private long insertMission(String type, String regionCode, String pathJson) {
		String title = "MSG222-svc-" + System.nanoTime();
		em.createNativeQuery("""
			INSERT INTO missions (type, title, region_code, start_at, end_at, target_count, path)
			VALUES (:type, :title, CAST(:region AS varchar), NULL, NULL, 1, CAST(:path AS jsonb))
			""")
			.setParameter("type", type)
			.setParameter("title", title)
			.setParameter("region", regionCode)
			.setParameter("path", pathJson)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	private void insertMissionGrid(long missionId, String gridId, Integer seq) {
		em.createNativeQuery("""
			INSERT INTO mission_grids (mission_id, grid_id, seq)
			VALUES (:missionId, :gridId, CAST(:seq AS integer))
			""")
			.setParameter("missionId", missionId)
			.setParameter("gridId", gridId)
			.setParameter("seq", seq)
			.executeUpdate();
	}

	private MissionResponseDto findMission(long missionId) {
		return newService().getActiveMissions().stream()
			.filter(dto -> dto.missionId() == missionId)
			.findFirst()
			.orElseThrow();
	}

	@Test
	@DisplayName("COURSE는 PATH shape로 path 원문과 스팟마커를 seq순으로 반환한다")
	void COURSE는_PATH_shape로_path_원문과_스팟마커를_seq순으로_반환한다() {
		String pathJson = "{\"type\":\"LineString\",\"coordinates\":[[129.04,35.10],[129.05,35.11]]}";
		long mission = insertMission("COURSE", null, pathJson);
		String first = gid(GY0, GX0);
		String second = gid(GY0 + 1, GX0 + 1);
		// seq 를 역순으로 넣어 정렬이 실제로 seq 오름차순인지 확인한다.
		insertMissionGrid(mission, second, 2);
		insertMissionGrid(mission, first, 1);

		PathShape shape = (PathShape) findMission(mission).shape();

		assertThat(shape.line()).contains("LineString").contains("coordinates");
		assertThat(shape.spots()).extracting(Spot::seq).containsExactly(1, 2);
		assertThat(shape.spots()).extracting(Spot::gridId).containsExactly(first, second);
		GridPoint firstCenter = GridEncoder.center(first);
		assertThat(shape.spots().get(0).lat()).isEqualTo(firstCenter.lat());
		assertThat(shape.spots().get(0).lng()).isEqualTo(firstCenter.lon());
	}

	@Test
	@DisplayName("path가 NULL인 COURSE도 PATH shape로 line=null 그대로 스팟마커만 반환한다")
	void path가_NULL인_COURSE도_PATH_shape로_line_null_그대로_스팟마커만_반환한다() {
		// chk_missions_path 는 COURSE 의 path NOT NULL 을 강제하지 않는다 — NULL path 미션이 들어와도
		// 크래시 없이 line=null passthrough 가 계약이다 (PR #58 리뷰 반영).
		long mission = insertMission("COURSE", null, null);
		String only = gid(GY0, GX0);
		insertMissionGrid(mission, only, 1);

		PathShape shape = (PathShape) findMission(mission).shape();

		assertThat(shape.line()).isNull();
		assertThat(shape.spots()).extracting(Spot::gridId).containsExactly(only);
	}

	@Test
	@DisplayName("EVENT는 BOX shape로 mission_grids를 감싸는 경계사각형을 합성한다")
	void EVENT는_BOX_shape로_mission_grids를_감싸는_경계사각형을_합성한다() {
		long mission = insertMission("EVENT", null, null);
		insertMissionGrid(mission, gid(GY0, GX0), null);
		insertMissionGrid(mission, gid(GY0 + 1, GX0 + 1), null);

		BoxShape shape = (BoxShape) findMission(mission).shape();

		// 남서→남동→북동→북서→남서 닫힌 링. 경계는 두 셀 bbox 의 전역 min/max.
		assertBoxEnvelope(shape, gid(GY0, GX0), gid(GY0 + 1, GX0 + 1));
	}

	@Test
	@DisplayName("단일격자 팝업EVENT는 BOX가 한 셀 사각형이다")
	void 단일격자_팝업EVENT는_BOX가_한_셀_사각형이다() {
		long mission = insertMission("EVENT", null, null);
		String grid = gid(GY0, GX0);
		insertMissionGrid(mission, grid, null);

		BoxShape shape = (BoxShape) findMission(mission).shape();

		// 한 격자면 경계 사각형 = 그 셀 하나를 감싸는 축정렬 사각형(≈마커). 5179 셀은 기울어져 있어
		// 셀 bbox 링 자체와는 다르다 — 네 꼭짓점을 감싸는 최소 사각형이다 (MSG-347).
		assertBoxEnvelope(shape, grid);
	}

	@Test
	@DisplayName("POPUP 미션은 BOX shape로 합성된다 — 9×9 격자를 감싸는 5점 닫힌 링")
	void POPUP_미션은_BOX_shape로_합성된다() {
		// 시더 산출물 형태(중심±4, 81격자, MSG-235 FR-2) 그대로 — EVENT 와 같은 BOX 재사용 (D5).
		long mission = insertMission("POPUP", null, null);
		for (long dy = -4; dy <= 4; dy++) {
			for (long dx = -4; dx <= 4; dx++) {
				insertMissionGrid(mission, gid(GY0 + dy, GX0 + dx), null);
			}
		}

		BoxShape shape = (BoxShape) findMission(mission).shape();

		assertThat(findMission(mission).type()).isEqualTo("POPUP");
		// 오라클이 DB 쪽이라 81셀 전량을 그대로 넘긴다 — 모서리 셀만 추리는 지름길이 필요 없다.
		assertBoxEnvelope(shape, allCells(4).toArray(String[]::new));
	}

	@Test
	@DisplayName("THEME는 CELLS shape로 각 격자 중심점을 반환한다")
	void THEME는_CELLS_shape로_각_격자_중심점을_반환한다() {
		long mission = insertMission("THEME", null, null);
		String a = gid(GY0, GX0);
		String b = gid(GY0 + 2, GX0 + 3);
		insertMissionGrid(mission, a, null);
		insertMissionGrid(mission, b, null);

		CellsShape shape = (CellsShape) findMission(mission).shape();

		assertThat(shape.cells()).extracting(Cell::gridId).containsExactlyInAnyOrder(a, b);
		Cell cellA = shape.cells().stream().filter(c -> c.gridId().equals(a)).findFirst().orElseThrow();
		GridPoint centerA = GridEncoder.center(a);
		assertThat(cellA.lat()).isEqualTo(centerA.lat());
		assertThat(cellA.lng()).isEqualTo(centerA.lon());
	}

	@Test
	@DisplayName("CONTINUOUS도 CELLS shape로 반환한다")
	void CONTINUOUS도_CELLS_shape로_반환한다() {
		long mission = insertMission("CONTINUOUS", null, null);
		String grid = gid(GY0, GX0);
		insertMissionGrid(mission, grid, null);

		CellsShape shape = (CellsShape) findMission(mission).shape();

		assertThat(shape.cells()).extracting(Cell::gridId).containsExactly(grid);
	}

	@Test
	@DisplayName("AREA는 REGION shape로 region_code만 반환한다")
	void AREA는_REGION_shape로_region_code만_반환한다() {
		String regionCode = "9995300020";
		String polygon = rectanglePolygonJson(127.0, 37.0, 127.01, 37.01);
		regionRepository.upsert(regionCode, "MSG222합성동", regionCode.substring(0, 5), polygon, CELL_AREA_M2);
		long mission = insertMission("AREA", regionCode, null);

		RegionShape shape = (RegionShape) findMission(mission).shape();

		assertThat(shape.regionCode()).isEqualTo(regionCode);
	}

	@Test
	@DisplayName("mission_grids가 비어도 방어적으로 빈 shape를 반환한다")
	void mission_grids가_비어도_방어적으로_빈_shape를_반환한다() {
		long box = insertMission("EVENT", null, null);
		long cells = insertMission("THEME", null, null);

		assertThat(((BoxShape) findMission(box).shape()).polygon()).isEmpty();
		assertThat(((CellsShape) findMission(cells).shape()).cells()).isEmpty();
	}

	@Test
	@DisplayName("PATH 스팟과 CELLS 중심점은 GridEncoder center와 일치한다")
	void PATH_스팟과_CELLS_중심점은_GridEncoder_center와_일치한다() {
		String grid = gid(GY0 + 5, GX0 + 7);
		GridPoint center = GridEncoder.center(grid);

		long course = insertMission("COURSE", null, "{\"type\":\"LineString\",\"coordinates\":[[1,1],[2,2]]}");
		insertMissionGrid(course, grid, 1);
		long theme = insertMission("THEME", null, null);
		insertMissionGrid(theme, grid, null);

		Spot spot = ((PathShape) findMission(course).shape()).spots().get(0);
		Cell cell = ((CellsShape) findMission(theme).shape()).cells().get(0);

		assertThat(spot.lat()).isEqualTo(center.lat());
		assertThat(spot.lng()).isEqualTo(center.lon());
		assertThat(cell.lat()).isEqualTo(center.lat());
		assertThat(cell.lng()).isEqualTo(center.lon());
	}

	@Test
	@DisplayName("BOX 경계사각형은 격자집합의 min max bbox와 일치한다")
	void BOX_경계사각형은_격자집합의_min_max_bbox와_일치한다() {
		long mission = insertMission("EVENT", null, null);
		List<String> grids = List.of(gid(GY0, GX0), gid(GY0 + 3, GX0 + 1), gid(GY0 + 1, GX0 + 4));
		grids.forEach(grid -> insertMissionGrid(mission, grid, null));

		BoxShape shape = (BoxShape) findMission(mission).shape();

		double minLat = Double.POSITIVE_INFINITY;
		double minLon = Double.POSITIVE_INFINITY;
		double maxLat = Double.NEGATIVE_INFINITY;
		double maxLon = Double.NEGATIVE_INFINITY;
		for (String grid : grids) {
			for (GridPoint corner : GridEncoder.bbox(grid)) {
				minLat = Math.min(minLat, corner.lat());
				minLon = Math.min(minLon, corner.lon());
				maxLat = Math.max(maxLat, corner.lat());
				maxLon = Math.max(maxLon, corner.lon());
			}
		}
		assertThat(shape.polygon()).containsExactly(
			new LatLng(minLat, minLon),
			new LatLng(minLat, maxLon),
			new LatLng(maxLat, maxLon),
			new LatLng(maxLat, minLon),
			new LatLng(minLat, minLon));
	}
}
