package com.msg.fillmap.region.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.region.entity.Region;

/**
 * 행정동 마스터 리포지토리 (MSG-154). 읽기는 JpaRepository 기본(count 등, 검증용),
 * 쓰기는 PostGIS 가 geometry 파싱·면적 산출을 한 문장에서 처리하는 native UPSERT.
 */
public interface RegionRepository extends JpaRepository<Region, String> {

	/**
	 * 행정동 한 건 멱등 UPSERT. geometryJson 을 ST_GeomFromGeoJSON 으로 파싱하고 ST_Multi 로
	 * MULTIPOLYGON 정규화한 뒤 GEOGRAPHY 로 캐스트해 저장한다. total_grid_count 는 경계 면적을
	 * 셀 면적(cellAreaM2)으로 나눠 시딩 시 1회 산출한다(D1, 면적 근사). ON CONFLICT 로 재실행 시 값이 수렴한다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO regions (region_code, region_name, parent_code, boundary_geom, total_grid_count)
		VALUES (
			:regionCode, :regionName, :parentCode,
			ST_Multi(ST_GeomFromGeoJSON(:geometryJson))::geography,
			ROUND(ST_Area(ST_Multi(ST_GeomFromGeoJSON(:geometryJson))::geography) / :cellAreaM2)
		)
		ON CONFLICT (region_code) DO UPDATE SET
			region_name      = EXCLUDED.region_name,
			parent_code      = EXCLUDED.parent_code,
			boundary_geom    = EXCLUDED.boundary_geom,
			total_grid_count = EXCLUDED.total_grid_count
		""", nativeQuery = true)
	int upsert(
		@Param("regionCode") String regionCode,
		@Param("regionName") String regionName,
		@Param("parentCode") String parentCode,
		@Param("geometryJson") String geometryJson,
		@Param("cellAreaM2") long cellAreaM2
	);

	/**
	 * 역지오코딩 (MSG-93): (lat, lon) 을 포함하는 행정동 1건. boundary_geom 이 GEOGRAPHY 이므로
	 * ST_Covers 로 GIST 인덱스(idx_regions_boundary)를 태운다 — ST_Contains(::geometry 캐스트)는 인덱스를
	 * 우회하므로 쓰지 않는다(§D2). 좌표 순서는 PostGIS 관례대로 ST_MakePoint(lon, lat)(X=경도). 경계선에 정확히
	 * 걸린 극소수 다중매칭은 ORDER BY region_code LIMIT 1 로 결정적으로 단일화한다 — recompute(refreshRegionStats)와
	 * 같은 규칙이라 역지오코딩과 수집률의 행정동 귀속이 항상 일치한다.
	 */
	@Query(value = """
		SELECT region_code AS "regionCode", region_name AS "regionName", parent_code AS "parentCode"
		FROM regions
		WHERE ST_Covers(boundary_geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography)
		ORDER BY region_code
		LIMIT 1
		""", nativeQuery = true)
	Optional<RegionProjection> findContainingRegion(@Param("lat") double lat, @Param("lon") double lon);

	/**
	 * 사용자 단위 advisory 트랜잭션 잠금 (MSG-155). 같은 사용자의 점령/롤백 트랜잭션이 겹칠 때
	 * recompute COUNT 가 서로의 미커밋 user_grids 를 못 봐 낮은 값으로 덮어쓰는 lost update 를 막는다 —
	 * 잠금 대기 후 실행되는 recompute 문장은 새 스냅샷(READ COMMITTED)으로 커밋된 진값을 센다.
	 * 트랜잭션 종료 시 자동 해제(xact lock). 키는 'region_stats:' 접두로 다른 advisory 사용처와 분리.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended('region_stats:' || :userId, 0))", nativeQuery = true)
	Object acquireUserRegionStatsLock(@Param("userId") long userId);

	/**
	 * parentCode 실존 검증 (MSG-156 §D3). regions.parent_code 에 그 시군구가 하나라도 있으면 true.
	 * 파생 쿼리라 idx_regions_parent 를 태운다 — geospatial 이 아니라 인덱스 equality 검사다.
	 * "데이터 없음"(유효 코드인데 수집 0)이 아니라 "존재하지 않는 코드"(오타·없는 시군구)만 6404 로 가른다.
	 */
	boolean existsByParentCode(String parentCode);

	/**
	 * 내 행정동별 수집률 조회 (MSG-156 §도메인 로직). region_stats 를 regions 와 PK equi-join 해
	 * regionName·parentCode 를 채운다 — geospatial 연산 0(성공 기준 8), 155 가 쓰기 시 물질화한 값을 그대로 읽는다.
	 * parentCode 가 null 이면 전국, 아니면 그 시군구 산하만(§D1). collectedOnly=true 면 collected_count>0 행만,
	 * false 면 손댄 행 전부(롤백 0-row 포함, 전체 regions outer-join 아님 — §D1). progress_rate 는 표시 100 clamp(§D4).
	 * nullable/boolean 파라미터는 PostgreSQL 타입 추론이 흔들리지 않도록 CAST 로 명시한다. LIMIT 없음 —
	 * 결과는 구조적으로 사용자당 최대 regions 행 수(3,558)로 유한해서, 인위적 상한은 조용한 절단만 만든다(§D2 정정).
	 */
	@Query(value = """
		SELECT
			rs.region_code     AS "regionCode",
			r.region_name      AS "regionName",
			r.parent_code      AS "parentCode",
			rs.collected_count AS "collectedCount",
			rs.total_count     AS "totalCount",
			LEAST(rs.progress_rate, 100.00) AS "progressRate",
			rs.updated_at      AS "updatedAt"
		FROM region_stats rs
		JOIN regions r ON r.region_code = rs.region_code
		WHERE rs.user_id = :userId
		  AND (CAST(:parentCode AS varchar) IS NULL OR r.parent_code = CAST(:parentCode AS varchar))
		  AND (CAST(:collectedOnly AS boolean) = FALSE OR rs.collected_count > 0)
		ORDER BY r.parent_code, r.region_name
		""", nativeQuery = true)
	List<RegionStatProjection> findStats(
		@Param("userId") long userId,
		@Param("parentCode") String parentCode,
		@Param("collectedOnly") boolean collectedOnly
	);

	/**
	 * 수집률 캐시(region_stats) recompute UPSERT (MSG-155). gridId 격자의 중심점(center_geom)을 덮는
	 * 행정동 1개를 ST_Covers 로 판정하고(경계선 위 다중매칭은 ORDER BY region_code LIMIT 1 로 결정적 단일화 —
	 * findContainingRegion·분자 카운트와 동일 규칙이라 한 격자가 두 행정동에 겹으로 잡히지 않는다), 그 (user, region) 의
	 * collected_count 를 "그 사용자가 그 행정동에서 점령(user_grids)한 격자 중 중심점이 그 행정동에 속하는 수"로
	 * 재계산한다. 분자는 videos 가 아니라 user_grids(격자 1 row) 라 경계 격자 이중 카운트가 없다(§D2).
	 * total_count 는 regions.total_grid_count 사본, progress_rate 는 ROUND(collected*100/total, 2) 물질화(§D4).
	 * 중심점을 덮는 행정동이 없으면(해안·무귀속) LATERAL 이 비어 SELECT 가 통째로 empty → 무변경 no-op(§D2).
	 * recompute 라 방향 무관·멱등 — 첫 점령/롤백 둘 다 이 한 문장(§D1·D5, 롤백 마지막 격자는 0 으로 UPSERT).
	 */
	@Modifying
	@Query(value = """
		INSERT INTO region_stats (user_id, region_code, collected_count, total_count, progress_rate, updated_at)
		SELECT
			:userId,
			tr.region_code,
			cnt.collected,
			tr.total_grid_count,
			COALESCE(ROUND(cnt.collected * 100.0 / NULLIF(tr.total_grid_count, 0), 2), 0.00),
			now()
		FROM grids g
		JOIN LATERAL (
			SELECT r0.region_code, r0.total_grid_count
			FROM regions r0
			WHERE ST_Covers(r0.boundary_geom, g.center_geom)
			ORDER BY r0.region_code
			LIMIT 1
		) tr ON TRUE
		JOIN LATERAL (
			SELECT COUNT(*) AS collected
			FROM user_grids ug
			JOIN grids g2 ON g2.grid_id = ug.grid_id
			WHERE ug.user_id = :userId AND tr.region_code = (
				SELECT r2.region_code
				FROM regions r2
				WHERE ST_Covers(r2.boundary_geom, g2.center_geom)
				ORDER BY r2.region_code
				LIMIT 1
			)
		) cnt ON TRUE
		WHERE g.grid_id = :gridId
		ON CONFLICT (user_id, region_code) DO UPDATE SET
			collected_count = EXCLUDED.collected_count,
			total_count     = EXCLUDED.total_count,
			progress_rate   = EXCLUDED.progress_rate,
			updated_at      = now()
		""", nativeQuery = true)
	int refreshRegionStats(@Param("userId") long userId, @Param("gridId") String gridId);
}
