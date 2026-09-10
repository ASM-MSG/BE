-- V37__users_terms_consent.sql
-- MSG-433: 회원가입 약관 동의 5항목 중 신규 4항목을 users 에 보관한다.
--
-- 카카오 로그인 동의 화면은 "카카오가 필맵에 정보를 제공해도 되는가"까지만 다루므로, 사업자 약관
-- 동의(만 14세 이상 확인·서비스 이용약관·개인정보 수집·이용·마케팅 정보 수신)는 필맵이 직접 받아
-- 증빙을 보관해야 한다 (PRD MSG-433 FR-2·4, SRS FR-USER-15).
--
-- 필수 3항목은 시각 컬럼 하나가 동의 여부와 시각을 겸한다 — NULL 이면 미동의, 값이 있으면 그 시각에
-- 동의다 (§D-2). 이 3항목은 철회 API 가 없어(철회 = 탈퇴) 한 번 켜지면 꺼지지 않는 값이라, boolean 을
-- 따로 두면 여부와 시각이 갈라질 자리만 생긴다. 컬럼 하나면 그 불일치가 표현 자체로 불가능하다.
--
-- 마케팅은 켜고 끄기를 반복하므로 현재 상태(boolean)와 마지막 변경 시각 쌍이다 (§D-4, V32 위치 동의
-- 미러). 위치기반서비스 항목은 신규 컬럼을 만들지 않고 V32 의 location_consent 쌍을 그대로 쓴다
-- (§D-3, 이중 저장 금지).
--
-- 기존 행은 시각 3종 NULL · 마케팅 DEFAULT FALSE 라 이 마이그레이션만으로 전원이 필수 동의 미완료로
-- 시작한다 (FR-7, 백필 없음). 시각의 저장 축은 UTC 다 (MSG-376). 탈퇴 시 users 행과 함께 사라지므로
-- 별도 정리가 없고, 이 컬럼들로 검색·정렬하는 요구가 없어 인덱스도 만들지 않는다.

ALTER TABLE users
	ADD COLUMN age_over14_consented_at      TIMESTAMP,
	ADD COLUMN service_terms_consented_at   TIMESTAMP,
	ADD COLUMN privacy_consented_at         TIMESTAMP,
	ADD COLUMN marketing_consent            BOOLEAN NOT NULL DEFAULT FALSE,
	ADD COLUMN marketing_consent_changed_at TIMESTAMP;
