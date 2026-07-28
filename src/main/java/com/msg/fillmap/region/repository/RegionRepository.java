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
	 * grids.region_code 멱등 보정 백필 (MSG-167 §D2 보정). V5 백필과 같은 판정·같은 IS NULL 가드다.
	 * V5 는 Flyway 시점(RegionSeeder 이전)에 돌아, "격자는 있는데 regions 가 빈" 환경에선 no-op 으로 끝나고
	 * upsertGrid 는 기존 격자를 건너뛰므로 라벨이 영구 NULL 로 남는다 — 시딩 직후 이 보정을 1회 돌려 닫는다.
	 * EXISTS 가드로 실제 라벨될 행만 갱신한다 — 무귀속(해안) 격자를 IS NULL 만으로 잡으면 PostgreSQL 이
	 * NULL→NULL 재기록(행 잠금·dead tuple·허위 카운트)을 만들어, 시딩 켠 기동마다 같은 행을 다시 쓴다.
	 * 라벨 완비 환경에선 진짜 0행 no-op. 실제 라벨된 행 수 반환.
	 */
	@Modifying
	@Query(value = """
		UPDATE grids g SET region_code = (
			SELECT r.region_code FROM regions r
			WHERE ST_Covers(r.boundary_geom, g.center_geom)
			ORDER BY r.region_code
			LIMIT 1)
		WHERE g.region_code IS NULL
		  AND EXISTS (
			SELECT 1 FROM regions r
			WHERE ST_Covers(r.boundary_geom, g.center_geom))
		""", nativeQuery = true)
	int backfillGridRegionCodes();

	/**
	 * 사용자 단위 advisory 트랜잭션 잠금 (MSG-155). 같은 사용자의 점령/롤백 트랜잭션이 겹칠 때
	 * recompute COUNT 가 서로의 미커밋 user_grids 를 못 봐 낮은 값으로 덮어쓰는 lost update 를 막는다 —
	 * 잠금 대기 후 실행되는 recompute 문장은 새 스냅샷(READ COMMITTED)으로 커밋된 진값을 센다.
	 * 트랜잭션 종료 시 자동 해제(xact lock). 키는 'region_stats:' 접두로 다른 advisory 사용처와 분리.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended('region_stats:' || :userId, 0))", nativeQuery = true)
	Object acquireUserRegionStatsLock(@Param("userId") long userId);

	/**
	 * 지역 검색 폴백 (MSG-234 §D6). zones.name 이 안 잡혔을 때만 도는 regions 이름 검색이다.
	 * region_name ILIKE 로 후보를 뽑고 각 경계의 ST_Envelope(GEOGRAPHY→geometry 캐스트) 로 bbox 를 낸다 —
	 * geospatial 이 여기서만 도는데 zone 미매치 branch + LIMIT 로 결과가 유한하고 사용자 타이핑 단발이라
	 * "조회/핫패스(뷰포트 루프) geospatial 금지" 취지에 어긋나지 않는다(MSG-93 저빈도 단발류 예외). 동명 행정동은
	 * parent_code 로 FE 가 구분한다. ORDER BY region_code 로 결과를 결정적으로 고정한다.
	 * q 의 %·_·\ 는 SQL 안에서 이스케이프해 리터럴로만 매치한다(백슬래시 먼저) — native 는 파생 쿼리와 달리
	 * 자동 이스케이프가 없어, q='%' 가 서비스의 빈 검색 가드를 우회해 전건 반환하는 것을 막는다.
	 */
	@Query(value = """
		SELECT region_code AS "regionCode", region_name AS "regionName", parent_code AS "parentCode",
		       ST_YMin(env) AS "minLat", ST_XMin(env) AS "minLon",
		       ST_YMax(env) AS "maxLat", ST_XMax(env) AS "maxLon"
		FROM (
			SELECT region_code, region_name, parent_code,
			       ST_Envelope(boundary_geom::geometry) AS env
			FROM regions
			WHERE region_name ILIKE
			      '%' || replace(replace(replace(:q, '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%' ESCAPE '\\'
			ORDER BY region_code
			LIMIT :limit
		) t
		""", nativeQuery = true)
	List<RegionSearchProjection> searchByName(@Param("q") String q, @Param("limit") int limit);

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
	 * 한 행정동의 내 수집률 단건 조회 (MSG-153 §도메인 로직 ②③). regionCode 는 resolveByPoint 가 판정한 실존 행정동이라
	 * regions 행이 반드시 있고, region_stats 를 LEFT JOIN 해 아직 수집이 없는(155 미실행) 행정동은 0% 로 합성한다 —
	 * collected_count 는 0, total_count 는 regions.total_grid_count 폴백, progress_rate 는 0.00, updated_at 은 null(§D6).
	 * region_stats 행이 있으면 그 값(progress_rate 는 156 D4 표시 100 clamp). geospatial 0 — 순수 equi 조회다.
	 * progress_rate 는 COALESCE 를 LEAST 안에 둔다 — PostgreSQL LEAST 는 NULL 인자를 건너뛰어 LEAST(NULL,100)=100 이 되므로,
	 * 미수집 행(rs.progress_rate NULL)을 먼저 0.00 으로 채운 뒤 100 clamp 해야 0% 합성이 100 으로 새지 않는다.
	 */
	@Query(value = """
		SELECT
			r.region_code                                   AS "regionCode",
			r.region_name                                   AS "regionName",
			r.parent_code                                   AS "parentCode",
			COALESCE(rs.collected_count, 0)                 AS "collectedCount",
			COALESCE(rs.total_count, r.total_grid_count)    AS "totalCount",
			LEAST(COALESCE(rs.progress_rate, 0.00), 100.00) AS "progressRate",
			rs.updated_at                                   AS "updatedAt"
		FROM regions r
		LEFT JOIN region_stats rs ON rs.region_code = r.region_code AND rs.user_id = :userId
		WHERE r.region_code = :regionCode
		""", nativeQuery = true)
	Optional<RegionStatProjection> findStatByRegion(
		@Param("userId") long userId,
		@Param("regionCode") String regionCode
	);

	/**
	 * 수집률 캐시(region_stats) recompute UPSERT (MSG-155, MSG-236 equi 치환). gridId 격자의 저장 라벨
	 * (grids.region_code — 167 이 쓰기 시 중심점 판정으로 1회 저장)을 regions 와 PK equi-join 해 귀속 행정동을 얻고,
	 * 그 (user, region) 의 collected_count 를 "그 사용자가 점령(user_grids)한 격자 중 저장 라벨이 그 행정동인 수"로
	 * 재계산한다. 두 판정 모두 저장 라벨 equi 라 ST_Covers 가 없다 — 167 이 라벨=라이브 판정을 고정해 등가다(§D1·D2).
	 * 다중매칭 타이브레이크(ORDER BY region_code)는 라벨 저장 시 이미 결정적으로 단일화돼 승계된다.
	 * 분자는 videos 가 아니라 user_grids(격자 1 row) 라 경계 격자 이중 카운트가 없다. total_count 는
	 * regions.total_grid_count 사본, progress_rate 는 ROUND(collected*100/total, 2) 물질화(§D4). 대상 격자가
	 * 무라벨(region_code IS NULL — 해안·무귀속)이면 가드로 SELECT 가 empty → 무변경 no-op(equi JOIN 도 NULL 을
	 * 탈락시키나 no-op 의도를 명시한다). recompute 라 방향 무관·멱등 — 첫 점령/롤백 둘 다 이 한 문장(롤백 마지막 격자는 0 으로 UPSERT).
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
		JOIN regions tr ON tr.region_code = g.region_code
		JOIN LATERAL (
			SELECT COUNT(*) AS collected
			FROM user_grids ug
			JOIN grids g2 ON g2.grid_id = ug.grid_id
			WHERE ug.user_id = :userId
			  AND g2.region_code = tr.region_code
		) cnt ON TRUE
		WHERE g.grid_id = :gridId
		  AND g.region_code IS NOT NULL
		ON CONFLICT (user_id, region_code) DO UPDATE SET
			collected_count = EXCLUDED.collected_count,
			total_count     = EXCLUDED.total_count,
			progress_rate   = EXCLUDED.progress_rate,
			updated_at      = now()
		""", nativeQuery = true)
	int refreshRegionStats(@Param("userId") long userId, @Param("gridId") String gridId);
}
