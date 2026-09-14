-- 뷰포트 캐시 실험용 시드. 서울 상자(37.45~37.65, 126.85~127.15)를 100m 격자로 전부 채우고,
-- 벤치 사용자(provider KAKAO · oid 'vbench-N', 개발용 로그인이 만든다)마다 그중 OCCUPY_RATIO 를 점령시킨다.
-- 격자 산술은 seed-dev-data.sql 과 같은 V28 함수 재사용 — 앱 GridEncoder 와 grid_id 가 글자 단위로 같다.
-- 변수: -v occupy=0.03
\set ON_ERROR_STOP on

CREATE FUNCTION pg_temp.cell_center(gy bigint, gx bigint) RETURNS geometry LANGUAGE sql IMMUTABLE AS $$
	SELECT ST_Transform(ST_SetSRID(ST_MakePoint((gx + 0.5) * 100, (gy + 0.5) * 100), 5179), 4326)
$$;
CREATE FUNCTION pg_temp.cell_bbox(gy bigint, gx bigint) RETURNS geometry LANGUAGE sql IMMUTABLE AS $$
	SELECT ST_Transform(ST_MakeEnvelope(gx * 100, gy * 100, (gx + 1) * 100, (gy + 1) * 100, 5179), 4326)
$$;

BEGIN;
SELECT setseed(0.42);

-- 서울 상자 네 모서리를 5179 로 옮겨 정수 인덱스 범위를 잡는다 (GridEncoder.viewportRange 와 같은 min/max).
CREATE TEMP TABLE box ON COMMIT DROP AS
SELECT floor(min(ST_Y(m)) / 100)::bigint AS gymin, floor(max(ST_Y(m)) / 100)::bigint AS gymax,
       floor(min(ST_X(m)) / 100)::bigint AS gxmin, floor(max(ST_X(m)) / 100)::bigint AS gxmax
FROM (VALUES (37.45, 126.85), (37.45, 127.15), (37.65, 127.15), (37.65, 126.85)) c(lat, lng),
     LATERAL (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(lng, lat), 4326), 5179) AS m) t;

INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
SELECT gy || '_' || gx, gy, gx, pg_temp.cell_center(gy, gx)::geography, pg_temp.cell_bbox(gy, gx)::geography
FROM box, generate_series(gymin, gymax) gy, generate_series(gxmin, gxmax) gx
ON CONFLICT (grid_id) DO NOTHING;

-- 벤치 사용자 × 서울 격자의 occupy 비율. 이전 회차 점령은 지우고 다시 뽑는다 (같은 setseed → 같은 집합).
DELETE FROM user_grids WHERE user_id IN (SELECT id FROM users WHERE provider = 'KAKAO' AND oid LIKE 'vbench-%');
INSERT INTO user_grids (user_id, grid_id, video_count)
SELECT u.id, g.grid_id, 1
FROM users u, box b
JOIN grids g ON g.grid_y BETWEEN b.gymin AND b.gymax AND g.grid_x BETWEEN b.gxmin AND b.gxmax
WHERE u.provider = 'KAKAO' AND u.oid LIKE 'vbench-%' AND random() < :occupy;

COMMIT;
VACUUM ANALYZE grids;
VACUUM ANALYZE user_grids;

SELECT (SELECT count(*) FROM grids) AS grids,
       (SELECT count(*) FROM users WHERE oid LIKE 'vbench-%') AS bench_users,
       (SELECT count(*) FROM user_grids) AS user_grids;
