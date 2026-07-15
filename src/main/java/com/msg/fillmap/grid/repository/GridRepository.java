package com.msg.fillmap.grid.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.grid.entity.Grid;

/**
 * 격자 조회 리포지토리 (MSG-73, read 전용). 색칠 판정은 언제나 로그인 사용자의 user_grids 로 제한한다
 * (개인 도감 — glossary). videos 테이블은 접근하지 않는다.
 * viewport 는 접근 A(정수 범위 스캔)·B(GIST 공간 쿼리)를 둘 다 제공한다 — 부하테스트로 채택 결정(보류).
 */
public interface GridRepository extends JpaRepository<Grid, String> {

	/**
	 * 단일 격자 색칠 상태: 내 user_grids row 의 video_count. row 가 없으면(미점령) empty.
	 */
	@Query(value = """
		SELECT ug.video_count
		FROM user_grids ug
		WHERE ug.user_id = :userId AND ug.grid_id = :gridId
		""", nativeQuery = true)
	Optional<Integer> findVideoCount(@Param("userId") long userId, @Param("gridId") String gridId);

	/**
	 * 접근 A — 정수 범위 스캔. bbox 를 grid_y/grid_x 정수 범위로 환산해 BETWEEN 으로 필터한다.
	 * uq_grids_yx(btree) 활용, PostGIS 연산 없음.
	 */
	@Query(value = """
		SELECT g.grid_id AS "gridId", g.grid_y AS "gridY", g.grid_x AS "gridX"
		FROM user_grids ug
		JOIN grids g ON g.grid_id = ug.grid_id
		WHERE ug.user_id = :userId
			AND g.grid_y BETWEEN :minY AND :maxY
			AND g.grid_x BETWEEN :minX AND :maxX
		""", nativeQuery = true)
	List<OccupiedGridProjection> findOccupiedInRange(
		@Param("userId") long userId,
		@Param("minY") long minY,
		@Param("maxY") long maxY,
		@Param("minX") long minX,
		@Param("maxX") long maxX
	);

	/**
	 * 접근 B — GIST 공간 쿼리. ST_MakeEnvelope 인자는 (경도, 위도) 순서다(PostGIS 축 순서).
	 * bbox_geom 이 GEOGRAPHY 라 envelope 도 ::geography 로 캐스트해 idx_grids_bbox(GIST) 를 태운다.
	 */
	@Query(value = """
		SELECT g.grid_id AS "gridId", g.grid_y AS "gridY", g.grid_x AS "gridX"
		FROM user_grids ug
		JOIN grids g ON g.grid_id = ug.grid_id
		WHERE ug.user_id = :userId
			AND ST_Intersects(
				g.bbox_geom,
				ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)::geography
			)
		""", nativeQuery = true)
	List<OccupiedGridProjection> findOccupiedByIntersects(
		@Param("userId") long userId,
		@Param("swLng") double swLng,
		@Param("swLat") double swLat,
		@Param("neLng") double neLng,
		@Param("neLat") double neLat
	);
}
