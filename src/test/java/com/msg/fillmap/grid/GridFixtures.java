package com.msg.fillmap.grid;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;

import jakarta.persistence.EntityManager;

/**
 * grid 조회 테스트용 시드 헬퍼. grids row(지오메트리 포함)와 user_grids row 를 네이티브로 삽입한다.
 * bbox_geom 은 셀 인덱스로부터 ST_MakeEnvelope((경도, 위도) 순서)로 만들어 실제 스키마와 정합한다.
 */
public final class GridFixtures {

	private GridFixtures() {
	}

	public static String gridId(long gridY, long gridX) {
		return gridY + "_" + gridX;
	}

	/**
	 * (gridY, gridX) 셀을 grids 에 삽입하고 grid_id 를 반환한다. 이미 있으면 no-op.
	 */
	public static String seedGrid(EntityManager em, long gridY, long gridX) {
		String gridId = gridId(gridY, gridX);
		double south = gridY * GRID_LAT_STEP;
		double north = (gridY + 1) * GRID_LAT_STEP;
		double west = gridX * GRID_LNG_STEP;
		double east = (gridX + 1) * GRID_LNG_STEP;
		double centerLat = (gridY + 0.5) * GRID_LAT_STEP;
		double centerLng = (gridX + 0.5) * GRID_LNG_STEP;

		em.createNativeQuery("""
			INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
			VALUES (
				:gridId, :gridY, :gridX,
				ST_SetSRID(ST_MakePoint(:centerLng, :centerLat), 4326)::geography,
				ST_MakeEnvelope(:west, :south, :east, :north, 4326)::geography
			)
			ON CONFLICT (grid_id) DO NOTHING
			""")
			.setParameter("gridId", gridId)
			.setParameter("gridY", gridY)
			.setParameter("gridX", gridX)
			.setParameter("centerLng", centerLng)
			.setParameter("centerLat", centerLat)
			.setParameter("west", west)
			.setParameter("south", south)
			.setParameter("east", east)
			.setParameter("north", north)
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
		double south = gridY * GRID_LAT_STEP;
		double north = (gridY + 1) * GRID_LAT_STEP;
		double west = gridX * GRID_LNG_STEP;
		double east = (gridX + 1) * GRID_LNG_STEP;
		double centerLat = (gridY + 0.5) * GRID_LAT_STEP;
		double centerLng = (gridX + 0.5) * GRID_LNG_STEP;

		em.createNativeQuery("""
			INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom, region_code)
			VALUES (
				:gridId, :gridY, :gridX,
				ST_SetSRID(ST_MakePoint(:centerLng, :centerLat), 4326)::geography,
				ST_MakeEnvelope(:west, :south, :east, :north, 4326)::geography,
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
			.setParameter("centerLng", centerLng)
			.setParameter("centerLat", centerLat)
			.setParameter("west", west)
			.setParameter("south", south)
			.setParameter("east", east)
			.setParameter("north", north)
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
	 */
	public static int seedGridBlock(EntityManager em, long minY, long maxY, long minX, long maxX) {
		return em.createNativeQuery("""
			INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
			SELECT yy || '_' || xx, yy, xx,
				ST_SetSRID(ST_MakePoint((xx + 0.5) * :lng, (yy + 0.5) * :lat), 4326)::geography,
				ST_MakeEnvelope(xx * :lng, yy * :lat, (xx + 1) * :lng, (yy + 1) * :lat, 4326)::geography
			FROM generate_series(:minY, :maxY) AS yy,
			     generate_series(:minX, :maxX) AS xx
			ON CONFLICT (grid_id) DO NOTHING
			""")
			.setParameter("lat", GRID_LAT_STEP)
			.setParameter("lng", GRID_LNG_STEP)
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
