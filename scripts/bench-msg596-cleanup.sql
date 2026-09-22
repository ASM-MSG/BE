-- MSG-596 유지 모드(keep=1)로 남긴 더미를 지운다. 벤치 식별자(b596-*, b596_*)만 건드린다.
--   docker exec -i fillmap-postgres psql -U user -d fillmap < scripts/bench-msg596-cleanup.sql
-- 함정(2026-09-12 실측): videos 를 지우면 건마다 user_grids.cover_video_id (ON DELETE SET NULL) 트리거가
-- 도는데 그 컬럼에 인덱스가 없어 user_grids 를 전수 스캔한다 — 같은 트랜잭션에서 먼저 지운 행도 dead tuple 로
-- 힙에 남아 있어 순서를 바꿔도 소용없다(111만 × 44만 행, 10분 초과 2회). 그래서 임시 인덱스를 만들고 지운다.
-- grids 도 같다: videos(grid_id) 는 부분 인덱스(idx_videos_grid_popular)뿐이라 FK 검사가 못 타고 격자 46만 건마다
-- videos 힙을 훑는다(10분 초과 1회). 두 컬럼 모두 임시 인덱스를 세우고 끝에 지운다.
-- 트랜잭션은 짧게 나눈다 — 하나로 묶으면 users 의 AccessShare 락이 남의 Flyway ALTER TABLE 을 막는다(실측).
\set ON_ERROR_STOP on
-- CONCURRENTLY: 운영 DB 에서 빌드 동안 user_grids·videos 쓰기(업로드·삭제)를 막지 않기 위해. 트랜잭션 밖이라 가능.
CREATE INDEX CONCURRENTLY IF NOT EXISTS tmp_b596_user_grids_cover ON user_grids (cover_video_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS tmp_b596_videos_grid ON videos (grid_id);
CREATE TEMP TABLE b596_uid AS SELECT id FROM users WHERE provider = 'KAKAO' AND oid LIKE 'b596-%';
DELETE FROM region_stats WHERE user_id IN (SELECT id FROM b596_uid);   -- 4단계 백필(bench-msg596-region-stats-backfill.sql) 정리
DELETE FROM user_grids WHERE user_id IN (SELECT id FROM b596_uid);
DELETE FROM videos WHERE user_id IN (SELECT id FROM b596_uid);
DELETE FROM grids WHERE grid_id LIKE 'b596\_%';
DELETE FROM users WHERE id IN (SELECT id FROM b596_uid);
DROP INDEX CONCURRENTLY tmp_b596_user_grids_cover;
DROP INDEX CONCURRENTLY tmp_b596_videos_grid;
ANALYZE users; ANALYZE grids; ANALYZE videos; ANALYZE user_grids;

\echo '### 정리 검증 (기대: 전부 0)'
SELECT (SELECT count(*) FROM users WHERE oid LIKE 'b596-%') AS bench_users,
       (SELECT count(*) FROM grids WHERE grid_id LIKE 'b596\_%') AS bench_grids,
       (SELECT count(*) FROM videos v JOIN users u ON u.id = v.user_id WHERE u.oid LIKE 'b596-%') AS bench_videos,
       (SELECT count(*) FROM user_grids WHERE grid_id LIKE 'b596\_%') AS bench_user_grids;
