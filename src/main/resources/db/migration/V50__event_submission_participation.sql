-- 이벤트 참여형 (MSG-502): 승인 이벤트 회차 아래 참여를 신청한다.
-- 승인 시 부모 회차의 event_locations 로 반영하는 쪽은 MSG-500 (PRD v2.2 확정 1).
ALTER TABLE event_submissions
	ADD COLUMN parent_event_occurrence_id BIGINT REFERENCES event_occurrences(id),
	ADD COLUMN participation_method TEXT;

COMMENT ON COLUMN event_submissions.parent_event_occurrence_id
	IS '참여 대상 승인 이벤트 회차. EVENT 유형 전용 (MSG-502)';
COMMENT ON COLUMN event_submissions.participation_method
	IS '참여 방식 서술. EVENT 유형 전용, 최소 10자 (#100 준용)';

ALTER TABLE event_submissions DROP CONSTRAINT chk_event_sub_type;
ALTER TABLE event_submissions ADD CONSTRAINT chk_event_sub_type
	CHECK (type IN ('FESTIVAL', 'POPUP', 'EVENT'));

-- EVENT 행에는 부모가 반드시 있고 다른 유형 행에는 없다. MSG-500 approve 가 이 불변식 위에서
-- 부모를 역참조하므로 DB 가 강제한다 (유형별 텍스트 필수는 V49 선례대로 앱 검증 13439).
ALTER TABLE event_submissions ADD CONSTRAINT chk_event_sub_parent
	CHECK ((type = 'EVENT') = (parent_event_occurrence_id IS NOT NULL));
