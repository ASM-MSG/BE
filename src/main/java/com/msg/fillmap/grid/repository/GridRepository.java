package com.msg.fillmap.grid.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.grid.entity.Grid;

public interface GridRepository extends JpaRepository<Grid, String> {

	/**
	 * 전역 격자 등록 (lazy insert) — 없을 때만 grids row를 만든다.
	 * v6: grid_y / grid_x(뷰포트 정수 스캔용)를 함께 채운다. region_code는 grids에 없다 (videos로 이동).
	 *
	 * @return 영향받은 row 수. 1 = 이번 호출이 전역 격자 등록을 일으켰다, 0 = 이미 있었다 (디버깅·로깅용 —
	 *         첫 점령 판정은 user_grids 쪽에서 한다)
	 */
	@Modifying
	@Query(value = """
		INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
		VALUES (
			:gridId,
			:gridY,
			:gridX,
			ST_SetSRID(ST_MakePoint(:centerLon, :centerLat), 4326)::geography,
			ST_SetSRID(ST_GeomFromText(:bboxWkt), 4326)::geography
		)
		ON CONFLICT (grid_id) DO NOTHING
		""", nativeQuery = true)
	int registerIfAbsent(@Param("gridId") String gridId,
			@Param("gridY") long gridY,
			@Param("gridX") long gridX,
			@Param("centerLat") double centerLat,
			@Param("centerLon") double centerLon,
			@Param("bboxWkt") String bboxWkt);
}
