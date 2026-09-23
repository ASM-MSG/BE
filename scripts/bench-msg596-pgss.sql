-- MSG-596 · 부하 회차 뒤 pg_stat_statements 로 도감 요약 쿼리의 DB 쪽 실측을 읽는다.
-- 전제: ALTER SYSTEM SET shared_preload_libraries='pg_stat_statements' → docker restart → CREATE EXTENSION.
-- 회차 시작 전 SELECT pg_stat_statements_reset(); 으로 비우고, 끝난 뒤 이 파일을 실행한다.
--   docker exec -i fillmap-postgres psql -U user -d fillmap < scripts/bench-msg596-pgss.sql
SELECT calls,
       round(mean_exec_time::numeric, 1)  AS mean_ms,
       round(max_exec_time::numeric, 1)   AS max_ms,
       round(total_exec_time::numeric / 1000, 1) AS total_s,
       shared_blks_hit, shared_blks_read, temp_blks_written,
       rows,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90) AS query
FROM pg_stat_statements
WHERE query ILIKE '%user_grids%' OR query ILIKE '%region_code%'
ORDER BY total_exec_time DESC
LIMIT 8;
