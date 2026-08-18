-- FRIEND(MSG-416)·MODERATION(MSG-417) 카테고리 확장. 기존 행 무변경, CHECK 상위집합 재정의 (V25·V26 형상).
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_category;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_category
	CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND', 'VIDEO', 'WEEKLY', 'FRIEND', 'MODERATION'));

-- opt_outs 에는 FRIEND 만 추가한다. MODERATION 은 수신 거부 대상이 아니므로(MSG-417 FR-7 확정) 의도적 제외.
ALTER TABLE notification_opt_outs DROP CONSTRAINT chk_notification_opt_outs_category;
ALTER TABLE notification_opt_outs ADD CONSTRAINT chk_notification_opt_outs_category
	CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND', 'VIDEO', 'WEEKLY', 'FRIEND'));
