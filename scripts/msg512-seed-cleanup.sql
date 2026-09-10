-- ============================================================================
-- MSG-512 시드 영상 정리 런북 (2026-08-30)
--
-- 실행 전제: 백업 완료 (~/fillmap-dev-backup-20260830-081543.sql, 150MB — 이미 떠 둠)
-- 실행 방법 (EC2에서):
--   ssh fillmap
--   docker exec -i fillmap-postgres-dev psql -U dev -d fillmap -v ON_ERROR_STOP=1 \
--     < msg512-seed-cleanup.sql
--   (이 파일을 서버로 옮기거나, 로컬에서: ssh fillmap "docker exec -i ..." < scripts/msg512-seed-cleanup.sql)
--
-- 무엇을 지우나 (2026-08-30 전수 실측):
--   ① 초기 수동 시드 영상 160건 (videos/original/.../seed-01·02 키, S3 객체 부재)
--      — 소유 계정(jiwoo·junho·minjun·강정민)은 보존, 영상만. 그 영상만으로 성립한
--        점령 154행은 점령 롤백 규칙대로 함께 삭제(잔존 실영상 겹침 0 실측)
--   ② @seed.local 계정 500개 — 가짜 영상 240,000건(dev.local URL, 전부 403)이
--      users FK CASCADE로 연쇄 삭제 (실영상 0·친구 관계 0 실측)
--   ③ @dev.local 계정 10개 — 부하테스트 잔재 (영상 40·점령 5·스탬프 4 연쇄)
--   ④ 참조가 전부 사라진 빈 grids 약 144,369행 (videos·user_grids·sponsor_ads 가드)
--
-- FK 실측 근거: users 참조 20개 중 event_submissions·org_account_requests만
-- NO ACTION(시드 계정 해당 없음), videos 참조는 user_grids.cover_video_id만
-- SET NULL 나머지 CASCADE. 전체가 한 트랜잭션이라 중간 실패 시 전량 원복된다.
-- ============================================================================
BEGIN;

-- 1. 초기 수동 시드 영상 삭제 (계정 보존)
DELETE FROM videos WHERE thumbnail_url LIKE 'videos/original/%seed-%';

-- 2. 점령 롤백 수동 이행: 영상이 하나도 남지 않은 점령 행 삭제
--    (앱의 롤백 로직은 영상 삭제 API 경유에만 걸리므로 SQL 삭제는 여기서 직접 이행.
--     cover_video_id는 FK SET NULL이 이미 처리했다)
DELETE FROM user_grids ug
WHERE NOT EXISTS (SELECT 1 FROM videos v WHERE v.user_id = ug.user_id AND v.grid_id = ug.grid_id);

-- 3. 시드 계정 삭제 — CASCADE가 영상·점령·신고·좋아요·스탬프·스트릭·알림을 연쇄 제거
DELETE FROM users WHERE email LIKE '%@seed.local';
DELETE FROM users WHERE email LIKE '%@dev.local';

-- 4. 아무 참조도 없는 빈 격자 삭제 (grids FK는 전부 NO ACTION이라 가드 필수)
DELETE FROM grids g
WHERE NOT EXISTS (SELECT 1 FROM videos v WHERE v.grid_id = g.grid_id)
  AND NOT EXISTS (SELECT 1 FROM user_grids ug WHERE ug.grid_id = g.grid_id)
  AND NOT EXISTS (SELECT 1 FROM sponsor_ads s WHERE s.grid_id = g.grid_id);

-- 검증: seed_residue·seed_users_residue·orphan_user_grids가 0이어야 정상
SELECT 'videos_total' AS k, COUNT(*)::text AS v FROM videos
UNION ALL SELECT 'seed_residue', COUNT(*)::text FROM videos
	WHERE thumbnail_url LIKE 'https://dev.local/%' OR thumbnail_url LIKE 'videos/original/%seed-%'
UNION ALL SELECT 'seed_users_residue', COUNT(*)::text FROM users
	WHERE email LIKE '%@seed.local' OR email LIKE '%@dev.local'
UNION ALL SELECT 'grids_total', COUNT(*)::text FROM grids
UNION ALL SELECT 'user_grids_total', COUNT(*)::text FROM user_grids
UNION ALL SELECT 'orphan_user_grids', COUNT(*)::text FROM user_grids ug
	WHERE NOT EXISTS (SELECT 1 FROM videos v WHERE v.user_id = ug.user_id AND v.grid_id = ug.grid_id);

COMMIT;
