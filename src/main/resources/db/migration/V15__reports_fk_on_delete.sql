-- 계정 삭제(MSG-205) 선행: reports 만 user FK 에 ON DELETE 정책이 없어 users 행 삭제가 FK 위반으로 막힌다.
-- 그가 한 신고(reporter_id)는 함께 삭제, 그가 검토자(reviewed_by)로 기록된 신고는 검토자만 비운다.
-- 기존 데이터 무변경 — 제약 교체뿐이라 롤백 부담 없음.
ALTER TABLE reports
	DROP CONSTRAINT reports_reporter_id_fkey,
	ADD CONSTRAINT reports_reporter_id_fkey
		FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE reports
	DROP CONSTRAINT reports_reviewed_by_fkey,
	ADD CONSTRAINT reports_reviewed_by_fkey
		FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL;
