-- ============================================================================
-- FillMap V12 — 미션(MISSION_COUNT) 뱃지 시딩 (MSG-223)
-- 미션 판정 훅(MissionAwardService → BadgeAwardService.award)과 한 세트로 활성화 —
-- MSG-239 §D2 위임 조건 이행 (훅 없는 시딩은 획득 불가 뱃지 노출).
-- 소급 블록 없음 (239 §D9 의 예외 — MSG-223 §데이터 모델): metric 원천인 user_missions 가
-- 빈 테이블이라 역산 대상이 없고(판정 엔진이 이 티켓에서 최초 도입), 전원 0부터 시작.
-- 임계값 1·5·10 은 2026-07-30 성민 확정(FR-17). 이후 축 시딩은 §D9 소급 패턴이 기본.
-- MISSION_COUNT 는 V9 CHECK 에 이미 포함 — DDL 불요, INSERT 시딩만. V1~V11 무수정(checksum).
-- ============================================================================
INSERT INTO badges (code, name, description, condition_type, condition_value) VALUES
	('MISSION_1',  '첫 스탬프',     '첫 미션을 완료했어요',   'MISSION_COUNT', '{"value": 1}'),
	('MISSION_5',  '스탬프 수집가', '미션 5개를 완료했어요',  'MISSION_COUNT', '{"value": 5}'),
	('MISSION_10', '미션 마스터',   '미션 10개를 완료했어요', 'MISSION_COUNT', '{"value": 10}')
ON CONFLICT (code) DO NOTHING;
