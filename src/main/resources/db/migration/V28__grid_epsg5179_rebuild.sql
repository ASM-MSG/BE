-- V28__grid_epsg5179_rebuild.sql
-- MSG-347: 격자 계산 규칙을 위경도 등간격 근사(0.0009°/0.00115°)에서 EPSG:5179 미터 평면으로 전환하고
-- 저장된 격자 참조를 전량 이행한다. 스키마(컬럼·타입·제약) 변경은 없고 데이터만 바뀐다.
--
-- 좌표 변환은 SRID 5179 로 넘긴다. 계약 문자열(아래 pg_temp.crs_5179)을 ST_Transform 인자로 직접 주면
-- PostGIS 가 행마다 PROJ 파이프라인을 새로 만들어 호출당 0.846ms 가 든다(SRID 경로는 좌표계 캐시를 타서
-- 0.008ms, 로컬 PostGIS 16-3.4 동일 식 실측 106배). 이행이 수 분간 멈춰 dev 배포 헬스체크가 터진 원인이었다.
-- 값은 같다 — 이 DB 의 spatial_ref_sys 5179 proj4text 는 계약 문자열과 바이트 단위로 동일하고,
-- 전국 5,000점 대조에서 두 경로의 grid_id 불일치가 0건이었다. pg_temp.crs_5179 는 그 동일성을 남기는
-- 기록으로만 남긴다(아래 사전 검사에서 실제로 대조한다).
--
-- 모든 재계산이 보존된 원본(videos.geom · grids.center_geom)에서 출발하므로 flyway clean 후 재실행해도
-- 같은 값으로 수렴한다(멱등). 전 구문이 트랜잭션 안전하다 — CONCURRENTLY 같은 비트랜잭션 구문 없음.
-- 기존 V1~V27 무수정(MSG-130 Flyway checksum 가드).

-- ============================================================================
-- 계약 정의 · 공용 산술 (pg_temp — 세션 종료 시 자동 소멸, 마이그레이션 전용)
-- ============================================================================
CREATE FUNCTION pg_temp.crs_5179() RETURNS text LANGUAGE sql IMMUTABLE AS $$
	SELECT '+proj=tmerc +lat_0=38 +lon_0=127.5 +k=0.9996 +x_0=1000000 +y_0=2000000 +ellps=GRS80 '
		|| '+towgs84=0,0,0,0,0,0,0 +units=m +no_defs'
$$;

-- SRID 경로를 쓰는 전제 검사: 이 DB 의 5179 정의가 앱·FE 계약 문자열과 다르면 격자가 미묘하게 어긋난
-- 채로 전량 이행된다. 사후 발견이 불가능한 종류의 오차라 이행 전에 막는다.
DO $$
DECLARE
	registered text;
BEGIN
	SELECT proj4text INTO registered FROM spatial_ref_sys WHERE srid = 5179;

	IF registered IS NULL THEN
		RAISE EXCEPTION 'spatial_ref_sys 에 SRID 5179 가 없어 이행할 수 없다';
	END IF;

	IF btrim(registered) <> btrim(pg_temp.crs_5179()) THEN
		RAISE EXCEPTION 'SRID 5179 정의가 계약 문자열과 다르다. 등록값=[%], 계약=[%]',
			registered, pg_temp.crs_5179();
	END IF;
END $$;

-- 위경도 점 → 새 격자 ID. floor 반열림 구간 [n*100m, (n+1)*100m) 로 GridEncoder.encode 와 같은 규칙이다.
CREATE FUNCTION pg_temp.grid_id_5179(point geometry) RETURNS varchar LANGUAGE sql IMMUTABLE AS $$
	SELECT (floor(ST_Y(m) / 100)::bigint || '_' || floor(ST_X(m) / 100)::bigint)::varchar
	FROM (SELECT ST_Transform(ST_SetSRID(point, 4326), 5179) AS m) t
$$;

-- 격자 인덱스 → 셀 중심 위경도. 5179 평면에서 중심을 잡고 4326 으로 되돌린다.
CREATE FUNCTION pg_temp.cell_center(grid_y bigint, grid_x bigint) RETURNS geometry LANGUAGE sql IMMUTABLE AS $$
	SELECT ST_Transform(
		ST_SetSRID(ST_MakePoint((grid_x + 0.5) * 100, (grid_y + 0.5) * 100), 5179), 4326)
$$;

-- 격자 인덱스 → 셀 경계 폴리곤. 5179 사각형의 꼭짓점 4점을 각각 되돌리므로 위경도 평면에서는
-- 자오선 수렴만큼 기울어진 사각형이 된다(FR-4 — 남서/북동 2점으로 복원 불가).
CREATE FUNCTION pg_temp.cell_bbox(grid_y bigint, grid_x bigint) RETURNS geometry LANGUAGE sql IMMUTABLE AS $$
	SELECT ST_Transform(
		ST_MakeEnvelope(grid_x * 100, grid_y * 100, (grid_x + 1) * 100, (grid_y + 1) * 100, 5179), 4326)
$$;

-- ============================================================================
-- 사전 검사: 서비스 범위 밖 영상 좌표 (FR-13)
-- EPSG:5179 는 한국 한정 투영이라 원거리 좌표는 변환이 발산해 비정상 grid_id 를 만든다. 범위 밖 기존
-- 데이터는 자동 변환 대상이 아니라 수동 결정 대상이라 이행을 중단한다.
-- 값의 정본은 앱의 com.msg.fillmap.global.geo.KoreaCoordinates (위도 33~39, 경도 124~132, 닫힌 구간).
-- ============================================================================
DO $$
DECLARE
	offending text;
BEGIN
	SELECT string_agg(id::text, ', ' ORDER BY id) INTO offending
	FROM videos
	WHERE ST_Y(geom::geometry) NOT BETWEEN 33.0 AND 39.0
		OR ST_X(geom::geometry) NOT BETWEEN 124.0 AND 132.0;

	IF offending IS NOT NULL THEN
		RAISE EXCEPTION '서비스 범위(위도 33~39, 경도 124~132) 밖 videos 행이 있어 이행을 중단한다: id=%', offending;
	END IF;
END $$;

-- ============================================================================
-- 0단계: 스냅숏
-- 구/신 격자 판별을 값 범위 휴리스틱(grid_y 크기 비교)이 아니라 이 스냅숏으로만 한다. 좌표를 지리적으로
-- 제한하지 않는 계약에서 값 범위는 구/신을 구별하는 근거가 못 되기 때문이다.
-- ============================================================================
DROP TABLE IF EXISTS pg_temp.old_grids;
CREATE TEMP TABLE old_grids ON COMMIT DROP AS SELECT grid_id FROM grids;

DROP TABLE IF EXISTS pg_temp.migration_counts;
CREATE TEMP TABLE migration_counts ON COMMIT DROP AS
SELECT (SELECT COUNT(*) FROM videos) AS video_count_before;

-- ============================================================================
-- 1단계: videos 새 인덱스 계산 (원본 좌표 geom 에서 재계산)
-- status 와 무관하게 전 행 계산한다 — DELETED 행도 grid_id 가 NOT NULL FK 라 새 셀이 필요하다.
-- ============================================================================
ALTER TABLE videos ADD COLUMN new_grid_id VARCHAR(20);
UPDATE videos SET new_grid_id = pg_temp.grid_id_5179(geom::geometry);

-- ============================================================================
-- 2단계: sponsor_ads · mission_grids 새 인덱스 계산
-- 두 테이블에는 원본 좌표가 없어 구 셀의 저장된 중심점을 원본으로 삼는다.
-- ============================================================================
DROP TABLE IF EXISTS pg_temp.sponsor_ad_map;
CREATE TEMP TABLE sponsor_ad_map ON COMMIT DROP AS
SELECT sa.id, pg_temp.grid_id_5179(g.center_geom::geometry) AS new_grid_id
FROM sponsor_ads sa
JOIN grids g ON g.grid_id = sa.grid_id;

-- mission_grids 는 grids FK 가 없어 row 부재 셀이 있을 수 있다(미방문 포토스팟). grids row 가 있으면
-- center_geom 을, 없으면 grid_id 를 구 규칙 산술((y+0.5)*0.0009, (x+0.5)*0.00115)로 되돌려 쓴다.
-- 구 스텝 상수가 레포에 리터럴로 남는 마지막 사용처다.
-- 파싱 결과가 위경도 유효 범위 밖이면(테스트 잔재 합성 키 예: 999901_500000 → 위도 899°) 재계산이 무의미해
-- new_grid_id 를 NULL 로 두고 그 행은 손대지 않는다.
DROP TABLE IF EXISTS pg_temp.mission_grid_map;
CREATE TEMP TABLE mission_grid_map ON COMMIT DROP AS
WITH parsed AS (
	SELECT mg.mission_id, mg.grid_id AS old_grid_id, mg.seq, g.center_geom,
		CASE WHEN mg.grid_id ~ '^-?\d+_-?\d+$'
			THEN (split_part(mg.grid_id, '_', 1)::bigint + 0.5) * 0.0009 END AS old_lat,
		CASE WHEN mg.grid_id ~ '^-?\d+_-?\d+$'
			THEN (split_part(mg.grid_id, '_', 2)::bigint + 0.5) * 0.00115 END AS old_lon
	FROM mission_grids mg
	LEFT JOIN grids g ON g.grid_id = mg.grid_id
)
SELECT mission_id, old_grid_id, seq,
	CASE
		WHEN center_geom IS NOT NULL THEN pg_temp.grid_id_5179(center_geom::geometry)
		WHEN old_lat BETWEEN -90 AND 90 AND old_lon BETWEEN -180 AND 180
			THEN pg_temp.grid_id_5179(ST_SetSRID(ST_MakePoint(old_lon, old_lat), 4326))
	END AS new_grid_id
FROM parsed;

-- 새 셀 집합 스냅숏 — grids row 가 필요한 참조는 videos 와 sponsor_ads 뿐이다(mission_grids 는 FK 없음).
DROP TABLE IF EXISTS pg_temp.new_grids;
CREATE TEMP TABLE new_grids ON COMMIT DROP AS
SELECT DISTINCT new_grid_id AS grid_id FROM videos
UNION
SELECT DISTINCT new_grid_id FROM sponsor_ad_map;

-- ============================================================================
-- 3단계: 새 grids 행 INSERT (중심점·경계 도형·행정동 라벨 재계산)
-- region_code 는 새 중심점으로 기존 판정 규칙 그대로 재판정한다(V5 백필·upsertGrid 인라인과 동일, FR-7).
-- first_seen_at 은 그 셀에 속한 영상들의 MIN(created_at), 영상 없는 셀(스폰서 전용)은 now().
-- ON CONFLICT DO UPDATE — 구/신 grid_id 가 우연히 겹쳐도 새 체계 값으로 덮어써 안전하게 수렴한다.
-- ============================================================================
INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom, region_code, first_seen_at)
SELECT c.grid_id, c.grid_y, c.grid_x,
	c.center::geography, pg_temp.cell_bbox(c.grid_y, c.grid_x)::geography,
	(SELECT r.region_code FROM regions r
		WHERE ST_Covers(r.boundary_geom, c.center::geography)
		ORDER BY r.region_code
		LIMIT 1),
	COALESCE((SELECT MIN(v.created_at) FROM videos v WHERE v.new_grid_id = c.grid_id), now())
FROM (
	SELECT n.grid_id,
		split_part(n.grid_id, '_', 1)::bigint AS grid_y,
		split_part(n.grid_id, '_', 2)::bigint AS grid_x,
		pg_temp.cell_center(split_part(n.grid_id, '_', 1)::bigint,
			split_part(n.grid_id, '_', 2)::bigint) AS center
	FROM new_grids n
) c
ON CONFLICT (grid_id) DO UPDATE SET
	center_geom = EXCLUDED.center_geom,
	bbox_geom = EXCLUDED.bbox_geom,
	region_code = EXCLUDED.region_code,
	first_seen_at = LEAST(grids.first_seen_at, EXCLUDED.first_seen_at);

-- ============================================================================
-- 4단계: videos.grid_id 교체
-- region_code 는 재판정하지 않는다 — 격자가 아니라 영상 좌표로 판정된 라벨이라(V1 설계) 격자 체계와 무관하다.
-- ============================================================================
UPDATE videos SET grid_id = new_grid_id;
ALTER TABLE videos DROP COLUMN new_grid_id;

-- ============================================================================
-- 5단계: user_grids 재구성 (FR-6)
-- 모집단은 status <> 'DELETED' — 현행 video_count 증감 규칙(업로드 확정 +1, 삭제 -1, 블라인드 불변)의
-- 순합과 같은 집합이다. cover_video_id 는 구 도감에서 대표였던 영상이 새 셀 그룹에 남아 있으면 승계하고
-- (여럿이면 MIN(id)) 없으면 NULL 이다 — 갤러리 조회가 최신순 폴백을 가져 계약상 허용된다.
-- 셀 재편으로 점령 격자 수는 달라질 수 있고, 줄어도 뱃지는 회수하지 않는다(FR-12).
-- ============================================================================
DROP TABLE IF EXISTS pg_temp.old_covers;
CREATE TEMP TABLE old_covers ON COMMIT DROP AS
SELECT DISTINCT cover_video_id FROM user_grids WHERE cover_video_id IS NOT NULL;

DELETE FROM user_grids;

INSERT INTO user_grids (user_id, grid_id, first_collected_at, last_uploaded_at, video_count, cover_video_id)
SELECT v.user_id, v.grid_id, MIN(v.created_at), MAX(v.created_at), COUNT(*),
	MIN(v.id) FILTER (WHERE v.id IN (SELECT cover_video_id FROM old_covers))
FROM videos v
WHERE v.status <> 'DELETED'
GROUP BY v.user_id, v.grid_id;

-- ============================================================================
-- 5b단계: region_stats 전량 재계산
-- 탐험률 통계는 user_grids(5단계 재구성)와 grids.region_code(3단계 재판정)에서 파생되는 캐시라,
-- 그대로 두면 각 지역에 다음 업로드가 올 때까지 이전 체계 값이 사용자에게 보이고 뱃지 판정도 그 값을 쓴다.
-- 집계 규칙은 RegionRepository.refreshRegionStats(MSG-155)를 그대로 옮긴 것이다 — 분자는 videos 가 아니라
-- user_grids(격자 1행)라 경계 격자 이중 카운트가 없고, total_count 는 regions.total_grid_count 사본,
-- progress_rate 는 ROUND(collected*100/total, 2)에 0 분모 가드(NULLIF·COALESCE)를 건 물질화다.
-- regions 와의 JOIN 이 무라벨 격자(region_code IS NULL — 해안·무귀속)를 자연히 제외한다.
-- 전량 DELETE 후 재INSERT 라 대응 (사용자, 지역) 쌍이 사라진 행은 이 과정에서 함께 없어진다.
-- ============================================================================
DELETE FROM region_stats;

INSERT INTO region_stats (user_id, region_code, collected_count, total_count, progress_rate, updated_at)
SELECT ug.user_id, tr.region_code, COUNT(*), tr.total_grid_count,
	COALESCE(ROUND(COUNT(*) * 100.0 / NULLIF(tr.total_grid_count, 0), 2), 0.00), now()
FROM user_grids ug
JOIN grids g ON g.grid_id = ug.grid_id
JOIN regions tr ON tr.region_code = g.region_code
GROUP BY ug.user_id, tr.region_code, tr.total_grid_count;

-- ============================================================================
-- 6단계: sponsor_ads · mission_grids 격자 참조 교체
-- ============================================================================
UPDATE sponsor_ads sa SET grid_id = m.new_grid_id
FROM sponsor_ad_map m
WHERE m.id = sa.id;

-- mission_grids 는 PK 가 (mission_id, grid_id)라 인접 포토스팟 둘이 같은 새 셀로 합쳐지면 충돌한다.
-- 충돌 행은 하나로 병합하고 seq 는 MIN 을 유지한다.
DELETE FROM mission_grids mg
USING mission_grid_map m
WHERE mg.mission_id = m.mission_id AND mg.grid_id = m.old_grid_id AND m.new_grid_id IS NOT NULL;

INSERT INTO mission_grids (mission_id, grid_id, seq)
SELECT mission_id, new_grid_id, MIN(seq)
FROM mission_grid_map
WHERE new_grid_id IS NOT NULL
GROUP BY mission_id, new_grid_id
ON CONFLICT (mission_id, grid_id) DO NOTHING;

-- 병합으로 격자 수가 줄어든 미션은 목표 수를 남은 격자 수로 클램프한다. 목표가 격자 수보다 크면
-- 그 미션은 영구 미달성이 된다(달성 판정 = 서로 다른 방문 격자 수 >= target_count).
-- 격자가 하나도 없는 미션은 제외한다 — chk_missions_target(target_count > 0)이 0 클램프를 금지하고,
-- 그런 미션은 이 이행과 무관하게 원래 달성 불가라 여기서 손댈 사안이 아니다.
UPDATE missions m SET target_count = c.grid_count
FROM (SELECT mission_id, COUNT(*) AS grid_count FROM mission_grids GROUP BY mission_id) c
WHERE c.mission_id = m.id AND m.target_count > c.grid_count;

-- ============================================================================
-- 7단계: 구 grids 행 DELETE
-- 참조를 전부 새 셀로 옮긴 뒤, 스냅숏 기준으로 구 행이면서 새 집합에 없는 행을 지운다.
-- 무참조 유출 행(테스트 누수 등)도 같은 규칙으로 함께 정리된다.
-- ============================================================================
DELETE FROM grids
WHERE grid_id IN (SELECT grid_id FROM old_grids)
	AND grid_id NOT IN (SELECT grid_id FROM new_grids);

-- ============================================================================
-- 8단계: 이행 검증 — 하나라도 어긋나면 RAISE EXCEPTION 으로 전체 롤백한다.
-- ============================================================================
DO $$
DECLARE
	video_before bigint;
	video_after bigint;
	occupied_sum bigint;
	live_videos bigint;
	grid_diff bigint;
	over_target bigint;
BEGIN
	SELECT video_count_before INTO video_before FROM migration_counts;
	SELECT COUNT(*) INTO video_after FROM videos;
	IF video_before <> video_after THEN
		RAISE EXCEPTION '영상 유실: 이행 전 %건, 이행 후 %건', video_before, video_after;
	END IF;

	SELECT COALESCE(SUM(video_count), 0) INTO occupied_sum FROM user_grids;
	SELECT COUNT(*) INTO live_videos FROM videos WHERE status <> 'DELETED';
	IF occupied_sum <> live_videos THEN
		RAISE EXCEPTION 'user_grids.video_count 합(%)이 삭제 제외 영상 수(%)와 다르다', occupied_sum, live_videos;
	END IF;

	-- 각 EXCEPT 를 괄호로 묶는다 — UNION 과 EXCEPT 는 우선순위가 같고 좌결합이라 괄호가 없으면
	-- ((grids EXCEPT new) UNION ALL new) EXCEPT grids 로 파싱돼 한 방향(신 전용)만 남는다.
	SELECT COUNT(*) INTO grid_diff FROM (
		(SELECT grid_id FROM grids EXCEPT SELECT grid_id FROM new_grids)
		UNION ALL
		(SELECT grid_id FROM new_grids EXCEPT SELECT grid_id FROM grids)
	) d;
	IF grid_diff <> 0 THEN
		RAISE EXCEPTION 'grids 행 집합이 새 셀 스냅숏과 다르다: 차집합 %건', grid_diff;
	END IF;

	SELECT COUNT(*) INTO over_target
	FROM missions m
	JOIN (SELECT mission_id, COUNT(*) AS grid_count FROM mission_grids GROUP BY mission_id) c
		ON c.mission_id = m.id
	WHERE m.target_count > c.grid_count;
	IF over_target <> 0 THEN
		RAISE EXCEPTION '목표 격자 수가 대상 격자 수보다 큰 미션 %건', over_target;
	END IF;
END $$;
