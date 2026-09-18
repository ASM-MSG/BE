-- dev 밀도 테스트용 합성 데이터 시드 (크롤링 대체).
-- 격자 인코딩은 V28__grid_epsg5179_rebuild.sql 의 함수를 그대로 재사용해 앱(GridEncoder)과
-- grid_id 가 글자 단위로 일치한다. 좌표는 SRID 5179 경로로 변환 — V28 사전검사가 이 PostGIS
-- 에서 계약 proj4 문자열과 동일 결과임을 이미 검증했다.
--
-- 시드 사용자는 email LIKE 'seed%@seed.local' 로 식별한다(cleanup 이 이 조건으로 되돌린다).
-- 변수: -v n_users=200 -v n_general=45000 -v n_hot=9000

\set ON_ERROR_STOP on

-- 격자 산술 (V28 재사용, 세션 임시 함수) ------------------------------------
CREATE FUNCTION pg_temp.grid_id_5179(point geometry) RETURNS varchar LANGUAGE sql IMMUTABLE AS $$
	SELECT (floor(ST_Y(m) / 100)::bigint || '_' || floor(ST_X(m) / 100)::bigint)::varchar
	FROM (SELECT ST_Transform(point, 5179) AS m) t
$$;
CREATE FUNCTION pg_temp.cell_center(gy bigint, gx bigint) RETURNS geometry LANGUAGE sql IMMUTABLE AS $$
	SELECT ST_Transform(ST_SetSRID(ST_MakePoint((gx + 0.5) * 100, (gy + 0.5) * 100), 5179), 4326)
$$;
CREATE FUNCTION pg_temp.cell_bbox(gy bigint, gx bigint) RETURNS geometry LANGUAGE sql IMMUTABLE AS $$
	SELECT ST_Transform(ST_MakeEnvelope(gx * 100, gy * 100, (gx + 1) * 100, (gy + 1) * 100, 5179), 4326)
$$;

BEGIN;

-- 1. 합성 사용자 -----------------------------------------------------------
INSERT INTO users (provider, oid, email, password_hash, nickname, friend_code, grid_color, role, email_verified, created_at)
SELECT 'LOCAL', NULL,
	'seed' || g || '@seed.local',
	'$2a$10$devseedplaceholderhashaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
	'dev-seed-' || g,
	'S' || lpad(g::text, 7, '0'),
	(ARRAY['BLUE','GREEN','PURPLE','ORANGE','PINK','YELLOW','RED','TEAL'])[1 + floor(random() * 8)::int],
	'USER', true, (now() AT TIME ZONE 'UTC')
FROM generate_series(1, :n_users) g;

-- 시드 사용자 id 는 연속 BIGSERIAL 이라 [umin, umin+n_users) 로 O(1) 랜덤 픽한다.
CREATE TEMP TABLE seed_meta ON COMMIT DROP AS
SELECT min(id) AS umin, count(*)::int AS un FROM users WHERE email LIKE 'seed%@seed.local';

-- 2. 좌표 표본 (전국 16개 도심 클러스터 + 최근 48h 핫스팟) -------------------
-- 도시는 가중치만큼 배열에 펼쳐 두고 점마다 배열 인덱스 하나를 랜덤으로 뽑는다
-- (ORDER BY random() 정렬을 점마다 도는 대신 O(1) 배열 접근 — 수백만 행 성능).
CREATE TEMP TABLE seed_pts ON COMMIT DROP AS
WITH cities(lat, lon, spread, w) AS (VALUES
	(37.5665, 126.9780, 0.130, 7),   -- 서울 (광역 ~14km)
	(35.1796, 129.0756, 0.100, 5),   -- 부산
	(37.4563, 126.7052, 0.080, 3),   -- 인천
	(35.8714, 128.6014, 0.080, 3),   -- 대구
	(36.3504, 127.3845, 0.060, 3),   -- 대전
	(35.1595, 126.8526, 0.055, 2),   -- 광주
	(35.5384, 129.3114, 0.050, 2),   -- 울산
	(37.2636, 127.0286, 0.070, 3),   -- 수원·경기 남부
	(33.4996, 126.5312, 0.090, 2),   -- 제주
	(37.7519, 128.8761, 0.045, 1),   -- 강릉
	(37.8813, 127.7300, 0.045, 1),   -- 춘천
	(36.6424, 127.4890, 0.050, 2),   -- 청주
	(35.8242, 127.1480, 0.050, 2),   -- 전주
	(36.0190, 129.3435, 0.045, 1),   -- 포항
	(34.8118, 126.3922, 0.040, 1),   -- 목포
	(37.6584, 126.8320, 0.055, 2)    -- 고양·김포
),
wc AS (
	SELECT row_number() OVER () AS rn, lat, lon, spread
	FROM cities, generate_series(1, w)
),
arr AS (   -- 세 배열을 같은 rn 순으로 모아 인덱스 정렬을 고정한다
	SELECT array_agg(lat ORDER BY rn) AS la, array_agg(lon ORDER BY rn) AS lo,
		array_agg(spread ORDER BY rn) AS sp, count(*)::int AS n
	FROM wc
),
raw AS (
	-- 일반 스캐터: 최근 90일. 도시 인덱스·오프셋 난수는 전부 generate_series 위 서브쿼리
	-- select-list 에 두어 행별 평가를 강제한다(바깥과 무상관인 LATERAL 은 쿼리당 1회로 호이스팅됨).
	SELECT (m.umin + floor(r.ru * m.un))::bigint AS user_id,
		a.lo[r.ci] + (r.rlon - 0.5) * 2 * a.sp[r.ci] AS lon,
		a.la[r.ci] + (r.rlat - 0.5) * 2 * a.sp[r.ci] AS lat,
		(now() AT TIME ZONE 'UTC') - (r.rt * 90) * interval '1 day' AS created_at
	FROM arr a, seed_meta m,
		LATERAL (
			SELECT 1 + floor(random() * a.n)::int AS ci,
				random() AS ru, random() AS rlon, random() AS rlat, random() AS rt
			FROM generate_series(1, :n_general)
		) r
	UNION ALL
	-- 핫스팟: 좁은 반경(~0.005°≈500m)에 몰고 최근 46h 안 → 핫구역 점화
	SELECT (m.umin + floor(r.ru * m.un))::bigint,
		a.lo[r.ci] + (r.rlon - 0.5) * 2 * 0.005,
		a.la[r.ci] + (r.rlat - 0.5) * 2 * 0.005,
		(now() AT TIME ZONE 'UTC') - (r.rt * 46) * interval '1 hour'
	FROM arr a, seed_meta m,
		LATERAL (
			SELECT 1 + floor(random() * a.n)::int AS ci,
				random() AS ru, random() AS rlon, random() AS rlat, random() AS rt
			FROM generate_series(1, :n_hot)
		) r
)
SELECT user_id, created_at,
	ST_SetSRID(ST_MakePoint(
		GREATEST(124.1, LEAST(131.9, lon)),
		GREATEST(33.1, LEAST(38.9, lat))), 4326) AS geom
FROM raw;

ALTER TABLE seed_pts ADD COLUMN grid_id varchar;
UPDATE seed_pts SET grid_id = pg_temp.grid_id_5179(geom);
CREATE INDEX ON seed_pts (grid_id);

-- 격자별 1회 집계 (상관 서브쿼리 회피 — 대용량에서 O(고유격자×전체점) 방지)
CREATE TEMP TABLE grid_agg ON COMMIT DROP AS
SELECT grid_id,
	split_part(grid_id, '_', 1)::bigint AS gy,
	split_part(grid_id, '_', 2)::bigint AS gx,
	MIN(created_at) AS first_seen
FROM seed_pts GROUP BY grid_id;

-- 3. grids lazy insert (중심·경계·행정동 라벨 재계산, V28 3단계와 동일) --------
INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom, region_code, first_seen_at)
SELECT a.grid_id, a.gy, a.gx,
	pg_temp.cell_center(a.gy, a.gx)::geography,
	pg_temp.cell_bbox(a.gy, a.gx)::geography,
	(SELECT r.region_code FROM regions r
		WHERE ST_Covers(r.boundary_geom, pg_temp.cell_center(a.gy, a.gx)::geography)
		ORDER BY r.region_code LIMIT 1),
	a.first_seen
FROM grid_agg a
ON CONFLICT (grid_id) DO NOTHING;

-- 4. videos (1행=1방문, READY/PUBLIC/ACTIVE 라 대표영상·전역목록에 노출) --------
INSERT INTO videos (user_id, grid_id, region_code, geom, duration_sec,
	processing_status, visibility, status, view_count, recorded_at, created_at, encoded_url, thumbnail_url)
SELECT p.user_id, p.grid_id, g.region_code, p.geom::geography,
	1 + floor(random() * 30)::int, 'READY', 'PUBLIC', 'ACTIVE',
	floor(random() * 500)::bigint, p.created_at, p.created_at,
	'https://dev.local/seed/' || p.grid_id || '.m3u8',
	'https://dev.local/seed/' || p.grid_id || '.jpg'
FROM seed_pts p JOIN grids g ON g.grid_id = p.grid_id;

-- 5. user_grids 재구성 (점령, V28 5단계와 동일 집계) --------------------------
INSERT INTO user_grids (user_id, grid_id, first_collected_at, last_uploaded_at, video_count, cover_video_id)
SELECT v.user_id, v.grid_id, MIN(v.created_at), MAX(v.created_at), COUNT(*), MIN(v.id)
FROM videos v
WHERE v.user_id IN (SELECT id FROM users WHERE email LIKE 'seed%@seed.local')
GROUP BY v.user_id, v.grid_id
ON CONFLICT (user_id, grid_id) DO NOTHING;

COMMIT;

-- 요약 --------------------------------------------------------------------
\echo '=== 시드 결과 ==='
SELECT 'users(seed)' AS what, count(*) FROM users WHERE email LIKE 'seed%@seed.local'
UNION ALL SELECT 'grids', count(*) FROM grids
UNION ALL SELECT 'videos', count(*) FROM videos
UNION ALL SELECT 'user_grids', count(*) FROM user_grids
UNION ALL SELECT 'grids(최근48h 신호)', count(DISTINCT grid_id) FROM videos
	WHERE created_at >= (now() AT TIME ZONE 'UTC') - interval '48 hours';
