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
}
