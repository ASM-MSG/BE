-- V40__event_notification.sql
-- MSG-442: 행사 알림 구독(FR-EVENT-06) + 알림 카테고리 EVENT 확장 + 일정 개정 번호.

-- 행 존재 = 해당 회차 알림 ON (opt-in. notification_opt_outs 의 거울상 방향).
-- 시각 DEFAULT 는 V33 선례를 따른다: CURRENT_TIMESTAMP 는 timestamptz 라 naive 컬럼 대입 시
-- 세션 타임존으로 변환돼 KST 세션에서 9시간 앞선 값이 저장된다. statement_timestamp() AT TIME
-- ZONE 'utc' 는 세션 타임존과 무관하게 항상 UTC 벽시계다 (created_at <= starts_at 판정 재료라
-- 어긋나면 시작 알림 대상 선별이 9시간 틀어진다).
CREATE TABLE event_notification_subscriptions (
	user_id             BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	event_occurrence_id BIGINT    NOT NULL REFERENCES event_occurrences(id) ON DELETE CASCADE,
	created_at          TIMESTAMP NOT NULL DEFAULT (statement_timestamp() AT TIME ZONE 'utc'),

	PRIMARY KEY (user_id, event_occurrence_id)
);

-- 스케줄러 fanout·종료 일괄 해제의 조회 키 (PK 는 user_id 선두라 회차 조회를 못 탄다)
CREATE INDEX idx_event_noti_sub_occurrence ON event_notification_subscriptions (event_occurrence_id);

-- 일정 개정 번호: 시각 변경 시에만 +1 (엔티티 update() 소유). 일정 변경 알림 dedupe 키 재료.
-- 시각값 키는 일정 왕복(A→B→A→B)에서 두 번째 변경 알림이 억제되므로 단조 증가 번호로 대체한다.
ALTER TABLE event_occurrences ADD COLUMN schedule_revision INT NOT NULL DEFAULT 0;

-- EVENT 카테고리 확장. 기존 행 무변경, CHECK 상위집합 재정의 (V35·V36 형상).
-- 'EVENT'(5자)는 notifications VARCHAR(10)·opt_outs VARCHAR(20) 폭 확장이 불요하다.
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_category;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_category
	CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND', 'VIDEO', 'WEEKLY', 'FRIEND', 'MODERATION', 'EVENT'));

ALTER TABLE notification_opt_outs DROP CONSTRAINT chk_notification_opt_outs_category;
ALTER TABLE notification_opt_outs ADD CONSTRAINT chk_notification_opt_outs_category
	CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND', 'VIDEO', 'WEEKLY', 'FRIEND', 'MISSION_NEARBY', 'EVENT'));
