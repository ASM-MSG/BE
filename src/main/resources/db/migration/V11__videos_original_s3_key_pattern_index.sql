-- V11__videos_original_s3_key_pattern_index.sql
-- MSG-262: 확정 경로(업로드·교체)의 이중 확정 검사 existsByOriginalS3KeyStartingWith(LIKE 'prefix%')가
-- MSG-247에서 등장 — 비-C 콜레이션 DB에선 기존 uq_videos_original_s3_key(btree)가 LIKE prefix 조회를
-- 못 타 확정마다 videos 풀 스캔(O(전체 영상)). varchar_pattern_ops 보조 인덱스로 인덱스 스캔을 보장한다.
-- 컬럼·데이터 변경 없음, 기존 V1~V10 무수정(MSG-130 checksum).

CREATE INDEX idx_videos_original_s3_key_pattern ON videos (original_s3_key varchar_pattern_ops);
