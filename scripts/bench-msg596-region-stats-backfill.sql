-- MSG-596 4단계 벤치 준비 · region_stats 백필 (로컬 전용)
-- 운영에선 첫 점령·점령 롤백마다 RegionRepository.refreshRegionStats 가 (user_id, region_code) 행을 갱신하지만,
-- 벤치 시드는 user_grids 를 직접 INSERT 해서 이 테이블이 비어 있다. refreshRegionStats 와 같은 정의로 채운다:
--   collected_count = 그 사용자가 그 행정동에서 점령한 격자 수 (user_grids ⨝ grids.region_code)
--   total_count     = regions.total_grid_count, progress_rate = collected/total*100 (소수 2자리)
-- 벤치 사용자(b596-*)만 대상. 실행: docker exec -i fillmap-postgres psql -U user -d fillmap < scripts/bench-msg596-region-stats-backfill.sql
\timing on
INSERT INTO region_stats (user_id, region_code, collected_count, total_count, progress_rate, updated_at)
SELECT ug.user_id, g.region_code, COUNT(*) AS collected, r.total_grid_count,
       COALESCE(ROUND(COUNT(*) * 100.0 / NULLIF(r.total_grid_count, 0), 2), 0.00),
       statement_timestamp() AT TIME ZONE 'UTC'
FROM user_grids ug
JOIN grids g   ON g.grid_id = ug.grid_id
JOIN regions r ON r.region_code = g.region_code
WHERE ug.user_id IN (SELECT id FROM users WHERE provider = 'KAKAO' AND oid LIKE 'b596-%')
  AND g.region_code IS NOT NULL
GROUP BY ug.user_id, g.region_code, r.total_grid_count
ON CONFLICT (user_id, region_code) DO UPDATE SET
  collected_count = EXCLUDED.collected_count,
  total_count     = EXCLUDED.total_count,
  progress_rate   = EXCLUDED.progress_rate,
  updated_at      = EXCLUDED.updated_at;
VACUUM ANALYZE region_stats;
-- 등가성 검사: 4단계 서브쿼리 == 1단계 서브쿼리 (사용자별로 다르면 실패)
SELECT COUNT(*) AS mismatch
FROM (
  SELECT u.id,
         (SELECT COUNT(*) FROM region_stats rs WHERE rs.user_id = u.id AND rs.collected_count > 0) AS by_stats,
         (SELECT COUNT(DISTINCT g.region_code) FROM user_grids ug JOIN grids g ON g.grid_id = ug.grid_id WHERE ug.user_id = u.id) AS by_grids
  FROM users u WHERE u.provider = 'KAKAO' AND u.oid LIKE 'b596-%'
) t WHERE by_stats <> by_grids;
