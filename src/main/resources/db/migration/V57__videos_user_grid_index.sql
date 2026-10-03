-- MSG-612: 사용자 + 격자 축의 영상 조회 인덱스. MSG-127 이 "한 격자당 소량이라 YAGNI" 로 미뤄 둔 것
-- (docs/spec/MSG-127.md:172). 2026-10-03 감사 실측(§3.1 #9)에서 격자 8.5만 사용자는 idx_videos_user_created
-- 로 사용자 전 행 85,083건을 긁고 grid_id 를 힙에서 걸러 격자 1건 14ms, 친구 도감 30칸 480ms 였다.
-- 태우는 경로 4개: 격자별 내 영상, 친구 격자 영상, 친구 도감 LATERAL 썸네일, 대표 영상 재선정.
-- created_at DESC 를 셋째 키로 둬 세 경로의 ORDER BY 가 Sort 없이 끝난다(재선정의 오름차순은 역방향 스캔).
-- Flyway 가 트랜잭션 안에서 돌려 CONCURRENTLY 를 못 쓴다. dev 와 prod 는 소규모라 잠금 시간이 짧다.
CREATE INDEX idx_videos_user_grid ON videos (user_id, grid_id, created_at DESC);
