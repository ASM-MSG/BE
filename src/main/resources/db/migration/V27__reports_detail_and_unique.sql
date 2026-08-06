-- 신고 접수(MSG-192): 상세 설명 컬럼 + 중복 신고 방지 유니크 제약.
-- reports 는 지금까지 쓰는 코드가 없어 기존 행이 0건, 제약 추가가 실패할 데이터 위험 없음 (PRD §4).
ALTER TABLE reports
	ADD COLUMN detail VARCHAR(500);

ALTER TABLE reports
	ADD CONSTRAINT uq_reports_reporter_video UNIQUE (reporter_id, video_id);
