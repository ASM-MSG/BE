-- VIDEO 카테고리 확장 (MSG-313). 기존 행 무변경, CHECK 를 상위집합으로 재정의 (docs/MSG-181.md 확장 지점 절).
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_category;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_category
	CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND', 'VIDEO'));

ALTER TABLE notification_opt_outs DROP CONSTRAINT chk_notification_opt_outs_category;
ALTER TABLE notification_opt_outs ADD CONSTRAINT chk_notification_opt_outs_category
	CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND', 'VIDEO'));
