-- MSG-494: 두 실행 노드가 함께 선점하는 영속 인코딩 작업 큐.
CREATE TABLE video_encoding_jobs (
	id              BIGSERIAL PRIMARY KEY,
	video_id        BIGINT       NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
	original_s3_key VARCHAR(500) NOT NULL,
	status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
	attempt_count   SMALLINT     NOT NULL DEFAULT 0,
	claim_token     UUID,
	claimed_by      VARCHAR(64),
	available_at    TIMESTAMP    NOT NULL DEFAULT (statement_timestamp() AT TIME ZONE 'utc'),
	lease_until     TIMESTAMP,
	enqueued_at     TIMESTAMP    NOT NULL DEFAULT (statement_timestamp() AT TIME ZONE 'utc'),
	completed_at    TIMESTAMP,
	last_error      VARCHAR(1000),

	CONSTRAINT uq_video_encoding_job_attempt UNIQUE (video_id, original_s3_key),
	CONSTRAINT chk_video_encoding_job_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD')),
	CONSTRAINT chk_video_encoding_job_attempt_count CHECK (attempt_count BETWEEN 0 AND 3)
);

CREATE INDEX idx_video_encoding_jobs_pending
	ON video_encoding_jobs (available_at, id)
	WHERE status = 'PENDING';

CREATE INDEX idx_video_encoding_jobs_processing
	ON video_encoding_jobs (lease_until, id)
	WHERE status = 'PROCESSING';

INSERT INTO video_encoding_jobs (video_id, original_s3_key)
SELECT id, original_s3_key
FROM videos
WHERE status = 'ACTIVE'
	AND processing_status IN ('UPLOADED', 'ENCODING')
	AND original_s3_key IS NOT NULL
ON CONFLICT (video_id, original_s3_key) DO NOTHING;
