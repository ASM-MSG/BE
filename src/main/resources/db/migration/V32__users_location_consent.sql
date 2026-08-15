-- V32__users_location_consent.sql
-- MSG-402: 위치기반서비스 이용 동의 여부와 마지막 변경 시각을 users 에 보관한다.
--
-- 위치정보법상 "언제 동의했고 언제 철회했나"에 답하려면 서버가 상태를 들고 있어야 하는데
-- 지금은 저장할 컬럼이 없어 프로필 편집의 "위치정보 사용" 토글이 임시 UI 로 떠 있다
-- (PRD MSG-402 FR-1~4, SRS FR-USER-14).
--
-- NOT NULL + DEFAULT FALSE 라 기존 사용자 전원이 이 마이그레이션만으로 미동의로 시작한다
-- (FR-8, 백필 스크립트 없음). 변경 시각은 한 번도 안 바꾼 사용자가 있으므로 NULL 허용이고,
-- 값이 실제로 달라질 때만 갱신된다(§D-4 원자 UPDATE). 이력 테이블은 PRD 비목표라 두지 않는다.
--
-- 탈퇴 시 users 행과 함께 사라져 별도 정리가 없다. 인덱스는 만들지 않는다 —
-- 이 컬럼으로 검색·정렬하는 요구가 없고, 읽기는 항상 id 로 찾은 본인 행 하나다.

ALTER TABLE users
	ADD COLUMN location_consent            BOOLEAN NOT NULL DEFAULT FALSE,
	ADD COLUMN location_consent_changed_at TIMESTAMP;
