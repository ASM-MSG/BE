-- V4__videos_blurring_started_at.sql
-- MSG-149 (D4 승격): AI 블러 타임아웃을 행 생성(created_at)이 아니라 "이번 BLURRING 시도 시작"으로 잰다.
-- 교체 후 재인코딩은 created_at 이 옛 시각이라 타임아웃이 100% 오탐(즉시 FAILED) → 시도별 시작시각으로 해소.
-- nullable, 백필 없음(운영에 BLURRING 행 없음). 기존 V1~V3 무수정(MSG-130).

ALTER TABLE videos
	ADD COLUMN blurring_started_at TIMESTAMP;   -- 이번 BLURRING 시도 시작 시각. markEncoded 가 세팅, 교체 시 null

COMMENT ON COLUMN videos.blurring_started_at IS 'AI 블러 시도 시작 시각 — 타임아웃 기준, 교체/재시도마다 갱신 (MSG-149 D4)';

-- 폴러의 대상 조회(findByStatusAndProcessingStatus(ACTIVE, BLURRING))용 부분 인덱스.
-- BLURRING 행은 전체의 극소수라 부분 인덱스가 작고, 폴 주기마다(30s) 도는 스캔을 인덱스-온리로 만족시킨다.
CREATE INDEX idx_videos_active_blurring ON videos (id)
	WHERE status = 'ACTIVE' AND processing_status = 'BLURRING';
