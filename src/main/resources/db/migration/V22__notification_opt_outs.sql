-- 알림 수신 거부 (MSG-180). 행 존재 = 해당 카테고리 off, 행 부재 = on (FR-7 opt-out 기본 전부 on).
CREATE TABLE notification_opt_outs (
	user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	category   VARCHAR(10) NOT NULL,
	created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

	PRIMARY KEY (user_id, category),
	CONSTRAINT chk_notification_opt_outs_category CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND'))
);
