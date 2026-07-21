package com.msg.fillmap.region.repository;

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
	 * 우회하므로 쓰지 않는다(§D2). 좌표 순서는 PostGIS 관례대로 ST_MakePoint(lon, lat)(X=경도). LIMIT 1 은
	 * 경계선에 정확히 걸린 극소수 다중매칭을 단일화한다(행정동 경계는 상호 배타라 실질적으로 1건).
	 */
	@Query(value = """
		SELECT region_code AS "regionCode", region_name AS "regionName", parent_code AS "parentCode"
		FROM regions
		WHERE ST_Covers(boundary_geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography)
		LIMIT 1
		""", nativeQuery = true)
	Optional<RegionProjection> findContainingRegion(@Param("lat") double lat, @Param("lon") double lon);

	/**
	 * 수집률 캐시(region_stats) recompute UPSERT (MSG-155). gridId 격자의 중심점(center_geom)을 덮는
	 * 행정동 1개를 ST_Covers 로 판정하고(경계선 상호 배타 → LIMIT 1 단일화), 그 (user, region) 의
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
			SELECT r0.region_code, r0.boundary_geom, r0.total_grid_count
			FROM regions r0
			WHERE ST_Covers(r0.boundary_geom, g.center_geom)
			LIMIT 1
		) tr ON TRUE
		JOIN LATERAL (
			SELECT COUNT(*) AS collected
			FROM user_grids ug
			JOIN grids g2 ON g2.grid_id = ug.grid_id
			WHERE ug.user_id = :userId AND ST_Covers(tr.boundary_geom, g2.center_geom)
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
