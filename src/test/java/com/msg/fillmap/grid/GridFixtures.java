package com.msg.fillmap.grid;

import static com.msg.fillmap.grid.GridConstants.CELL_SIZE_METERS;
import static com.msg.fillmap.grid.GridConstants.CRS_DEF_EPSG5179;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.video.support.GeoSupport;

import jakarta.persistence.EntityManager;

/**
 * grid 조회 테스트용 시드 헬퍼. grids row(지오메트리 포함)와 user_grids row 를 네이티브로 삽입한다.
 * 지오메트리는 EPSG:5179 셀 경계를 4326 으로 되돌려 만들어 실제 스키마·프로덕션 저장값과 정합한다.
 */
public final class GridFixtures {

	// 셀 좌표 → 위경도 역변환. 정의는 GridEncoder 와 같은 계약 문자열이라 두 경로가 어긋날 수 없다.
	private static final CRSFactory CRS_FACTORY = new CRSFactory();
	private static final CoordinateTransform TO_DEGREES = new CoordinateTransformFactory().createTransform(
		CRS_FACTORY.createFromParameters("EPSG:5179", CRS_DEF_EPSG5179),
		CRS_FACTORY.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs"));

	private GridFixtures() {
	}

	public static String gridId(long gridY, long gridX) {
		return gridY + "_" + gridX;
	}

	/**
	 * 격자 좌표계 위의 한 점을 위경도로 준다. cellY·cellX 는 셀 인덱스이고 소수를 허용한다 —
	 * {@code pointAt(gridY + 0.5, gridX + 0.5)} 는 셀 중심, {@code pointAt(gridY + 0.7, gridX + 0.2)} 는
	 * 셀 안 임의 지점이다. 셀 인덱스로부터 테스트 좌표를 만드는 단일 경로다 (MSG-347).
	 */
	public static GridPoint pointAt(double cellY, double cellX) {
		ProjCoordinate degrees = TO_DEGREES.transform(
			new ProjCoordinate(cellX * CELL_SIZE_METERS, cellY * CELL_SIZE_METERS), new ProjCoordinate());
		return new GridPoint(degrees.y, degrees.x);
	}

	/**
	 * (gridY, gridX) 셀을 grids 에 삽입하고 grid_id 를 반환한다. 이미 있으면 no-op.
	 */
	public static String seedGrid(EntityManager em, long gridY, long gridX) {
		String gridId = gridId(gridY, gridX);
		GridPoint center = pointAt(gridY + 0.5, gridX + 0.5);

		em.createNativeQuery("""
			INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
			VALUES (
				:gridId, :gridY, :gridX,
				ST_SetSRID(ST_MakePoint(:centerLng, :centerLat), 4326)::geography,
				ST_GeomFromText(:bboxWkt, 4326)::geography
			)
			ON CONFLICT (grid_id) DO NOTHING
			""")
			.setParameter("gridId", gridId)
			.setParameter("gridY", gridY)
			.setParameter("gridX", gridX)
			.setParameter("centerLng", center.lon())
			.setParameter("centerLat", center.lat())
			.setParameter("bboxWkt", GeoSupport.bboxWkt(gridId))
			.executeUpdate();
		return gridId;
	}

	/**
	 * seedGrid 와 같되 region_code 를 중심점 판정으로 라벨해 삽입한다(upsertGrid·V5 백필과 동일 규칙 인라인).
	 * 라벨이 붙으려면 중심점을 덮는 regions 가 먼저 시드돼 있어야 한다 — 무귀속(해안)이면 서브쿼리가 NULL 이다.
	 * 저장 라벨을 equi 로 읽는 경로(refreshRegionStats 등)의 픽스처가 프로덕션 "탄생 시 라벨"을 재현한다. 이미 있으면 no-op.
	 */
	public static String seedLabeledGrid(EntityManager em, long gridY, long gridX) {
		String gridId = gridId(gridY, gridX);
		GridPoint center = pointAt(gridY + 0.5, gridX + 0.5);

		em.createNativeQuery("""
			INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom, region_code)
			VALUES (
				:gridId, :gridY, :gridX,
				ST_SetSRID(ST_MakePoint(:centerLng, :centerLat), 4326)::geography,
				ST_GeomFromText(:bboxWkt, 4326)::geography,
				(SELECT r.region_code FROM regions r
					WHERE ST_Covers(r.boundary_geom, ST_SetSRID(ST_MakePoint(:centerLng, :centerLat), 4326)::geography)
					ORDER BY r.region_code
					LIMIT 1)
			)
			ON CONFLICT (grid_id) DO NOTHING
			""")
			.setParameter("gridId", gridId)
			.setParameter("gridY", gridY)
			.setParameter("gridX", gridX)
			.setParameter("centerLng", center.lon())
			.setParameter("centerLat", center.lat())
			.setParameter("bboxWkt", GeoSupport.bboxWkt(gridId))
			.executeUpdate();
		return gridId;
	}

	/**
	 * (userId, gridId) 점령 row 를 user_grids 에 삽입한다(video_count 지정).
	 */
	public static void seedUserGrid(EntityManager em, long userId, String gridId, int videoCount) {
		em.createNativeQuery("""
			INSERT INTO user_grids (user_id, grid_id, video_count)
			VALUES (:userId, :gridId, :videoCount)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("videoCount", videoCount)
			.executeUpdate();
	}

	/**
	 * 대량 시드 — [minY..maxY] × [minX..maxX] 직사각 블록의 grids row 를 set-based INSERT 로 채운다.
	 * 부하테스트/EXPLAIN 벤치마크용 현실적 볼륨을 만든다. 반환값은 삽입된 grids row 수.
	 * 행마다 Java 로 역변환하면 왕복이 커져 셀 경계를 5179 평면에서 만든 뒤 PostGIS 로 한 번에 역변환한다
	 * (좌표계 정의는 앱과 같은 계약 문자열 — V28 마이그레이션과 같은 방식).
	 */
	public static int seedGridBlock(EntityManager em, long minY, long maxY, long minX, long maxX) {
		return em.createNativeQuery("""
			INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
			SELECT yy || '_' || xx, yy, xx,
				ST_SetSRID(ST_Transform(
					ST_MakePoint((xx + 0.5) * :cell, (yy + 0.5) * :cell), :def5179, :defWgs84), 4326)::geography,
				ST_SetSRID(ST_Transform(
					ST_MakeEnvelope(xx * :cell, yy * :cell, (xx + 1) * :cell, (yy + 1) * :cell),
					:def5179, :defWgs84), 4326)::geography
			FROM generate_series(:minY, :maxY) AS yy,
			     generate_series(:minX, :maxX) AS xx
			ON CONFLICT (grid_id) DO NOTHING
			""")
			.setParameter("cell", (double) CELL_SIZE_METERS)
			.setParameter("def5179", CRS_DEF_EPSG5179)
			.setParameter("defWgs84", "+proj=longlat +datum=WGS84 +no_defs")
			.setParameter("minY", minY)
			.setParameter("maxY", maxY)
			.setParameter("minX", minX)
			.setParameter("maxX", maxX)
			.executeUpdate();
	}

	/**
	 * 대량 시드 — 블록 내에서 (yy + xx) % modulo == 0 인 셀을 userId 의 점령으로 채운다(부분 밀도).
	 * 반환값은 삽입된 user_grids row 수. grids 블록을 먼저 시드해야 FK 를 만족한다.
	 */
	public static int seedUserGridBlock(
		EntityManager em, long userId, long minY, long maxY, long minX, long maxX, int modulo) {
		return em.createNativeQuery("""
			INSERT INTO user_grids (user_id, grid_id, video_count)
			SELECT :userId, yy || '_' || xx, 1
			FROM generate_series(:minY, :maxY) AS yy,
			     generate_series(:minX, :maxX) AS xx
			WHERE mod(yy + xx, :modulo) = 0
			ON CONFLICT (user_id, grid_id) DO NOTHING
			""")
			.setParameter("userId", userId)
			.setParameter("minY", minY)
			.setParameter("maxY", maxY)
			.setParameter("minX", minX)
			.setParameter("maxX", maxX)
			.setParameter("modulo", modulo)
			.executeUpdate();
	}
}
