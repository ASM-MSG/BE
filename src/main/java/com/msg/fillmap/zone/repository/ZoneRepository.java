package com.msg.fillmap.zone.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.zone.entity.Zone;

/**
 * 구역(zone) 리포지토리 (MSG-234). geospatial 0 — 조회는 findAll 목록뿐(검색은 MSG-251 카카오 프록시 이관).
 * 쓰기는 시더의 멱등 UPSERT(zone_key 자연키) 하나뿐이라 native 로 둔다.
 */
public interface ZoneRepository extends JpaRepository<Zone, Long> {

	/**
	 * 구역 한 건 멱등 UPSERT (MSG-234 §D7). zone_key 가 자연키라 재실행 시 이름/사각형/priority 가 수렴한다.
	 * 시더만 호출한다(운영 중 CRUD 는 후속). ON CONFLICT (zone_key) DO UPDATE.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO zones (zone_key, name, region_code, min_grid_y, max_grid_y, min_grid_x, max_grid_x, priority)
		VALUES (:zoneKey, :name, :regionCode, :minGridY, :maxGridY, :minGridX, :maxGridX, :priority)
		ON CONFLICT (zone_key) DO UPDATE SET
			name        = EXCLUDED.name,
			region_code = EXCLUDED.region_code,
			min_grid_y  = EXCLUDED.min_grid_y,
			max_grid_y  = EXCLUDED.max_grid_y,
			min_grid_x  = EXCLUDED.min_grid_x,
			max_grid_x  = EXCLUDED.max_grid_x,
			priority    = EXCLUDED.priority
		""", nativeQuery = true)
	int upsert(
		@Param("zoneKey") String zoneKey,
		@Param("name") String name,
		@Param("regionCode") String regionCode,
		@Param("minGridY") int minGridY,
		@Param("maxGridY") int maxGridY,
		@Param("minGridX") int minGridX,
		@Param("maxGridX") int maxGridX,
		@Param("priority") int priority
	);
}
