-- 알림 딥링크 이동 대상 (MSG-432 D-1). 기존 행은 NULL = 대상 없음(앱은 지도 홈).
-- 종류와 식별자는 함께 있거나 함께 없다 — native INSERT 문장이 둘이라 DB 가 최종 방어선이다.
ALTER TABLE notifications
	ADD COLUMN target_type VARCHAR(20),
	ADD COLUMN target_id   VARCHAR(64),
	ADD CONSTRAINT chk_notifications_target_pair
		CHECK ((target_type IS NULL) = (target_id IS NULL)),
	ADD CONSTRAINT chk_notifications_target_type
		CHECK (target_type IN ('VIDEO', 'GRID', 'BADGE', 'EVENT_OCCURRENCE', 'USER'));
