-- V16__videos_fail_reason.sql
-- MSG-286: AI 프리체크 탈락 사유 코드. NULL = 시스템 오류 실패(타임아웃·AI FAILED·인코딩 실패).
-- 기존 FAILED 행은 전부 NULL — 프리체크 도입 전 실패라 시스템 오류 해석이 사실과 일치한다.

ALTER TABLE videos
	ADD COLUMN fail_reason VARCHAR(64);

COMMENT ON COLUMN videos.fail_reason IS '실패 사유 코드 — 프리체크 탈락 시 콜론 앞 안정 식별자(too_dark 등), NULL=시스템 오류 (MSG-286)';
