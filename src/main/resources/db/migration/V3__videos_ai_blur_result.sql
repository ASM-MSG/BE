-- V3__videos_ai_blur_result.sql
-- MSG-145: AI 하이라이트·블러 결과 저장 스키마.
-- V2(MSG-132)가 선점 → V3. 기존 V 파일 수정 금지(MSG-130).

ALTER TABLE videos
	ADD COLUMN blurred_s3_key VARCHAR(500),  -- 블러 처리본 S3 key (1:1). BE가 AI에서 받아 S3 업로드(MSG-150)
	ADD COLUMN highlights     JSONB,         -- 하이라이트 구간 [[시작초,끝초],...] 최대 3. 0구간이면 [] 또는 NULL
	ADD COLUMN ai_job_id      VARCHAR(64);   -- AI 잡 상관키. 폴링 복구용(MSG-149 폴링, MSG-150 결과반영)

-- 최대 3구간 불변식을 DB에서 강제(AI가 malformed 반환해도 방어 — trust boundary)
ALTER TABLE videos
	ADD CONSTRAINT chk_videos_highlights_len
	CHECK (highlights IS NULL OR jsonb_array_length(highlights) <= 3);

COMMENT ON COLUMN videos.blurred_s3_key IS '블러 처리본 S3 key (MSG-145)';
COMMENT ON COLUMN videos.highlights     IS '하이라이트 구간 [[시작초,끝초],...] 최대 3 (MSG-145)';
COMMENT ON COLUMN videos.ai_job_id      IS 'AI 서버 job_id 상관키 (MSG-145)';
