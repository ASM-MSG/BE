#!/usr/bin/env bash
# 시드 데이터 원복 — 콘텐츠 테이블 TRUNCATE + 시드 사용자 + hotzone 키 제거.
# 주의: dev 밀도 테스트 샌드박스 전용. 콘텐츠 테이블(videos·grids·user_grids 등)을 통째로
# 비운다 — 이 DB의 콘텐츠는 전부 시드가 만든 것이라는 전제다. 사용자 계정은 seed%@seed.local 만 지운다.
# (수백만 행 cascade DELETE 는 IO 로 병리적으로 느려 TRUNCATE 로 간다 — 2026-08-21 실측.)
set -euo pipefail
PG="${PG_CONTAINER:-fillmap-postgres}"
RD="${REDIS_CONTAINER:-fillmap-local-redis}"

# videos·grids 를 참조하는 테이블까지 한 문장에 묶는다(개별 TRUNCATE 는 FK 로 거부됨, CASCADE 미사용).
# event_videos 는 V39 부터 videos 를 참조한다 — 빠지면 TRUNCATE 전체가 거부된다 (MSG-456 Codex 리뷰 적발).
# event_video_comments·event_video_helpfuls 는 V41 부터 event_videos 를 참조한다 — 같은 이유로 동봉 (MSG-457 Codex 리뷰 적발).
# video_encoding_jobs 는 V44 부터 videos 를 참조한다 — 같은 이유로 동봉 (MSG-514 Codex 리뷰 적발).
docker exec -i "$PG" psql -U user -d fillmap -q <<'SQL'
\set ON_ERROR_STOP on
TRUNCATE grids, videos, user_grids, region_stats, sponsor_ads, likes, reports, event_videos, event_video_comments, event_video_helpfuls, video_encoding_jobs RESTART IDENTITY;
DELETE FROM users WHERE email LIKE 'seed%@seed.local';
SELECT 'videos' AS what, count(*) FROM videos
UNION ALL SELECT 'grids', count(*) FROM grids
UNION ALL SELECT 'user_grids', count(*) FROM user_grids
UNION ALL SELECT 'users(잔존)', count(*) FROM users;
SQL

docker exec "$RD" sh -c 'redis-cli --scan --pattern "hotzone:*" | xargs -r redis-cli DEL' >/dev/null
echo ">> cleanup 완료 (hotzone 키 제거)"
