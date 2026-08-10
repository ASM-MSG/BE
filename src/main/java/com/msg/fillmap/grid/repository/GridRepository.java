package com.msg.fillmap.grid.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.grid.entity.Grid;

/**
 * 격자 조회 리포지토리 (MSG-73, read 전용). 색칠 판정은 언제나 로그인 사용자의 user_grids 로 제한한다
 * (개인 도감 — glossary). videos 테이블은 접근하지 않는다.
 * viewport 실경로는 접근 A(정수 범위 스캔)로 확정(MSG-90, k6 부하테스트 판정).
 * 접근 B(GIST)는 벤치마크 이력 보존용으로만 남긴다(실경로 미사용 — MSG-90 Open Q1=b).
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
	 * regions 는 LEFT JOIN 이다 (MSG-349) — 무귀속 격자(region_code NULL)를 INNER JOIN 으로 떨구면
	 * 색칠된 칸이 지도에서 사라진다. 이름만 비고 칸은 남는 게 계약이다.
	 */
	@Query(value = """
		SELECT g.grid_id AS "gridId", g.grid_y AS "gridY", g.grid_x AS "gridX",
			r.region_name AS "regionName"
		FROM user_grids ug
		JOIN grids g ON g.grid_id = ug.grid_id
		LEFT JOIN regions r ON r.region_code = g.region_code
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
	 * 뷰포트 점령 격자를 행정 단위로 묶어 센 집계 (MSG-356). FROM·WHERE 술어가 findOccupiedInRange 와
	 * 글자 그대로 같아(같은 접근 A 범위 스캔) 세는 행 집합이 개별 조회와 동일하다 — 합이 어긋날 구조가 없다(FR-3).
	 * 묶음 키는 행정동 코드를 단위 길이(codeLen)로 자른 접두이고, 이름은 전체 경로 이름의 nameToken 번째 토큰이다.
	 * 같은 묶음의 이름은 접두가 같아 어차피 한 값이지만 집계 함수 자리라 MIN 으로 결정적으로 단일화한다.
	 * regions 는 LEFT JOIN 이다 — INNER 로 떨구면 무귀속 격자가 총합에서 사라져 FR-7 이 깨진다.
	 * substring(NULL) 이 NULL 이라 무귀속 행은 특례 코드 없이 NULL 키 한 그룹으로 모인다.
	 * center_geom 은 GEOGRAPHY 라 평면 함수(ST_X/ST_Y)를 쓰려면 ::geometry 캐스트가 필요하다.
	 * GROUP BY·ORDER BY 가 식 대신 출력 컬럼 번호(1)인 이유: 같은 식을 두 번 쓰면 codeLen 바인딩이
	 * 서로 다른 파라미터 자리($1, $6)가 돼 PostgreSQL 이 두 식을 같은 것으로 못 보고
	 * "column g.region_code must appear in the GROUP BY clause" 로 거절한다 (실측).
	 */
	@Query(value = """
		SELECT
			substring(g.region_code FROM 1 FOR :codeLen)     AS "regionCode",
			MIN(split_part(r.region_name, ' ', :nameToken))  AS "name",
			AVG(ST_Y(g.center_geom::geometry))               AS "lat",
			AVG(ST_X(g.center_geom::geometry))               AS "lng",
			COUNT(*)                                         AS "count"
		FROM user_grids ug
		JOIN grids g ON g.grid_id = ug.grid_id
		LEFT JOIN regions r ON r.region_code = g.region_code
		WHERE ug.user_id = :userId
			AND g.grid_y BETWEEN :minY AND :maxY
			AND g.grid_x BETWEEN :minX AND :maxX
		GROUP BY 1
		ORDER BY 1 NULLS LAST
		""", nativeQuery = true)
	List<RegionAggregateProjection> aggregateOccupiedInRange(
		@Param("userId") long userId,
		@Param("minY") long minY,
		@Param("maxY") long maxY,
		@Param("minX") long minX,
		@Param("maxX") long maxX,
		@Param("codeLen") int codeLen,
		@Param("nameToken") int nameToken
	);

	/**
	 * 접근 A 페이지 — 첫 페이지 (MSG-90 keyset). ORDER BY (grid_y, grid_x) 가 uq_grids_yx(btree)
	 * 정렬과 일치해 추가 정렬 비용이 없다. OFFSET 미사용, LIMIT 은 서비스의 lookahead(size + 1)다.
	 * regions LEFT JOIN(MSG-349)은 정렬 키와 무관해 keyset 순서·lookahead 판정을 바꾸지 않는다.
	 */
	@Query(value = """
		SELECT g.grid_id AS "gridId", g.grid_y AS "gridY", g.grid_x AS "gridX",
			r.region_name AS "regionName"
		FROM user_grids ug
		JOIN grids g ON g.grid_id = ug.grid_id
		LEFT JOIN regions r ON r.region_code = g.region_code
		WHERE ug.user_id = :userId
			AND g.grid_y BETWEEN :minY AND :maxY
			AND g.grid_x BETWEEN :minX AND :maxX
		ORDER BY g.grid_y, g.grid_x
		LIMIT :limit
		""", nativeQuery = true)
	List<OccupiedGridProjection> findOccupiedPage(
		@Param("userId") long userId,
		@Param("minY") long minY,
		@Param("maxY") long maxY,
		@Param("minX") long minX,
		@Param("maxX") long maxX,
		@Param("limit") int limit
	);

	/**
	 * 접근 A 페이지 — 커서 이후 (MSG-90 keyset). PostgreSQL 행 값 비교(row-value comparison)로
	 * (cursorY, cursorX) 보다 큰 격자만 grid_y, grid_x 순으로 이어서 반환한다.
	 * regions LEFT JOIN(MSG-349)은 정렬 키와 무관해 keyset 순서·lookahead 판정을 바꾸지 않는다.
	 */
	@Query(value = """
		SELECT g.grid_id AS "gridId", g.grid_y AS "gridY", g.grid_x AS "gridX",
			r.region_name AS "regionName"
		FROM user_grids ug
		JOIN grids g ON g.grid_id = ug.grid_id
		LEFT JOIN regions r ON r.region_code = g.region_code
		WHERE ug.user_id = :userId
			AND g.grid_y BETWEEN :minY AND :maxY
			AND g.grid_x BETWEEN :minX AND :maxX
			AND (g.grid_y, g.grid_x) > (:cursorY, :cursorX)
		ORDER BY g.grid_y, g.grid_x
		LIMIT :limit
		""", nativeQuery = true)
	List<OccupiedGridProjection> findOccupiedPageAfter(
		@Param("userId") long userId,
		@Param("minY") long minY,
		@Param("maxY") long maxY,
		@Param("minX") long minX,
		@Param("maxX") long maxX,
		@Param("cursorY") long cursorY,
		@Param("cursorX") long cursorX,
		@Param("limit") int limit
	);

	/**
	 * 격자 여러 개의 행정동 이름을 한 번에 읽는다 (MSG-349, 핫구역 상위 K ≤ 50건). 결과가 응답 항목이 아니라
	 * "gridId → 이름" 사전이라 여기는 INNER JOIN 이다 — 무귀속 격자는 결과에 안 나타나고 맵 miss 로 null 이
	 * 된다. Collectors.toMap 은 null 값에서 NPE 를 던지므로 null 행을 아예 안 받는 쪽이 조립을 단순하게 한다.
	 * 빈 목록으로 부르면 IN () 이 SQL 문법 오류라 호출부가 0건일 때 호출을 생략한다.
	 */
	@Query(value = """
		SELECT g.grid_id AS "gridId", r.region_name AS "regionName"
		FROM grids g
		JOIN regions r ON r.region_code = g.region_code
		WHERE g.grid_id IN (:gridIds)
		""", nativeQuery = true)
	List<GridRegionNameProjection> findRegionNames(@Param("gridIds") Collection<String> gridIds);

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
