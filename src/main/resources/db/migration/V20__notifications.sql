-- 알림 outbox 겸 발송 기록 (MSG-179). 비즈니스 트랜잭션과 같은 커밋으로 기록돼 유실이 없다 (FR-3).
CREATE TABLE notifications (
	id          BIGSERIAL    PRIMARY KEY,
	user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	category    VARCHAR(10)  NOT NULL,
	event_key   VARCHAR(100) NOT NULL,
	title       VARCHAR(100) NOT NULL,
	body        VARCHAR(255) NOT NULL,
	status      VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
	retry_count INT          NOT NULL DEFAULT 0,
	last_error  VARCHAR(255),
	created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
	sent_at     TIMESTAMP,

	CONSTRAINT chk_notifications_category CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND')),
	CONSTRAINT chk_notifications_status
		CHECK (status IN ('PENDING', 'PUBLISHED', 'SENT', 'SKIPPED', 'DEAD')),
	CONSTRAINT uq_notifications_user_event UNIQUE (user_id, event_key)   -- FR-6 멱등 1차 방어
);

-- 릴레이 폴링 전용 partial index — PENDING만 스캔 (WHERE status = 'PENDING' ORDER BY id LIMIT n)
CREATE INDEX idx_notifications_pending ON notifications (id) WHERE status = 'PENDING';
-- 전송률 제한 카운트(user_id, category, sent_at 조건) + user CASCADE 삭제용 (PG는 FK 인덱스 자동 생성 안 함 — V1 idx_push_tokens_user 선례)
CREATE INDEX idx_notifications_user ON notifications (user_id);
