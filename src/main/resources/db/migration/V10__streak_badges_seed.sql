-- ============================================================================
-- FillMap V10 — 꾸준함(STREAK_DAYS) 뱃지 시딩 (MSG-200)
-- 스트릭 갱신 훅(StreakCommandService → BadgeAwardService.award)과 한 세트로 활성화 —
-- MSG-239 §D2 위임 조건 이행 (훅 없는 시딩은 획득 불가 뱃지 노출).
-- 소급 블록 없음 (239 §D9 의 예외 — MSG-200 §D6): metric 원천인 streaks 가 빈 테이블이라
-- 역산 대상이 없고(출시 전), 전원 0부터 시작으로 확정. 이후 축 시딩은 §D9 소급 패턴이 기본.
-- STREAK_DAYS 는 V1 CHECK 에 이미 포함 — DDL 불요, INSERT 시딩만. V1~V9 무수정(checksum).
-- ============================================================================
INSERT INTO badges (code, name, description, condition_type, condition_value) VALUES
	('STREAK_3',  '꾸준함의 시작', '3일 연속 기록했어요',  'STREAK_DAYS', '{"value": 3}'),
	('STREAK_7',  '일주일 개근',   '7일 연속 기록했어요',  'STREAK_DAYS', '{"value": 7}'),
	('STREAK_30', '한 달 개근',    '30일 연속 기록했어요', 'STREAK_DAYS', '{"value": 30}')
ON CONFLICT (code) DO NOTHING;
