-- 벤치 사용자(501) · 서울 중심 0.04°×0.05° 뷰포트 → 5179 인덱스 범위 → 첫 페이지 쿼리 EXPLAIN
WITH b AS (
  SELECT floor(ST_Y(sw)/100)::bigint miny, floor(ST_Y(ne)/100)::bigint maxy, floor(ST_X(sw)/100)::bigint minx, floor(ST_X(ne)/100)::bigint maxx
  FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(126.95,37.50),4326),5179) sw, ST_Transform(ST_SetSRID(ST_MakePoint(127.00,37.54),4326),5179) ne) p
)
SELECT * FROM b;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT g.grid_id, g.grid_y, g.grid_x, r.region_name
FROM user_grids ug JOIN grids g ON g.grid_id = ug.grid_id LEFT JOIN regions r ON r.region_code = g.region_code
WHERE ug.user_id = 501
  AND g.grid_y BETWEEN 19505 AND 19551 AND g.grid_x BETWEEN 9536 AND 9581
ORDER BY g.grid_y, g.grid_x LIMIT 501;
