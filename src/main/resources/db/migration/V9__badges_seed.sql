-- ============================================================================
-- FillMap V9 — 뱃지 마스터 시딩·획득 엔진 스키마 (MSG-239)
-- 1) condition_type CHECK 확장: MISSION_COUNT 값 추가 (미션 뱃지의 판정 훅은 미션 판정 엔진 티켓 몫 —
--    여기서는 그 티켓이 스키마를 안 건드리도록 허용 값만 미리 넓혀 둔다)
-- 2) user_badges 컬럼 2개 추가: notified_at(사용자가 획득을 확인했는지 — NULL 이면 "새 뱃지" 표시 대상)
--    · featured_rank(닉네임 옆 대표 뱃지 표시 순서)
-- 3) 지금 지급 가능한 3종류 11개만 시딩. 스트릭·미션·오픈기념은 판정 로직이 아직 없어서 일부러 안 넣는다 —
--    시딩만 하면 영원히 획득 불가인 뱃지가 목록에 노출되므로, 각 판정 로직 티켓이 훅과 시딩을 세트로 넣는다
-- 4) 소급 지급: 시딩 직후 같은 파일에서 기존 데이터 기준 일괄 지급 — ON CONFLICT 라 재실행해도 안전(멱등).
--    이후 새 뱃지를 추가하는 마이그레이션도 이 시딩+소급 패턴을 복제한다. 상세 결정: docs/MSG-239.md §D2·D8·D9
-- 기존 V1~V8 무수정(checksum). 로컬 공유 DB엔 이 파일만 신규 적용(clean 불요 — 순수 추가).
-- ============================================================================

-- 1) CHECK 확장
ALTER TABLE badges DROP CONSTRAINT chk_badges_condition;
ALTER TABLE badges ADD CONSTRAINT chk_badges_condition CHECK (condition_type IN
	('REGION_PERCENT', 'TOTAL_GRIDS', 'STREAK_DAYS', 'UPLOAD_COUNT', 'MISSION_COUNT', 'SPECIAL'));

-- 2) user_badges 확장
ALTER TABLE user_badges
	ADD COLUMN notified_at   TIMESTAMP,          -- NULL = 사용자가 아직 못 본 뱃지 (소급 지급분). 응답에 실려 나간 동기 지급은 now()
	ADD COLUMN featured_rank SMALLINT,           -- NULL = 대표 아님. 1·2 = 닉네임 옆 표시 순서
	ADD CONSTRAINT chk_user_badges_featured_rank CHECK (featured_rank IN (1, 2));
-- 사용자당 rank 1·2 각 1개 → 대표 최대 2개를 앱 코드가 아니라 DB 제약이 강제
CREATE UNIQUE INDEX uq_user_badges_featured ON user_badges (user_id, featured_rank)
	WHERE featured_rank IS NOT NULL;

-- 3) 마스터 시딩 (수치 = BE 제안값, 팀 컨펌 시 이 값만 수정. icon_url 은 디자인 에셋 확정 시 UPDATE)
INSERT INTO badges (code, name, description, condition_type, condition_value) VALUES
	('EXPLORER_1',      '첫 발자국',   '첫 격자를 수집했어요',            'TOTAL_GRIDS',    '{"value": 1}'),
	('EXPLORER_10',     '탐험가 I',    '격자 10개를 수집했어요',          'TOTAL_GRIDS',    '{"value": 10}'),
	('EXPLORER_50',     '탐험가 II',   '격자 50개를 수집했어요',          'TOTAL_GRIDS',    '{"value": 50}'),
	('EXPLORER_100',    '탐험가 III',  '격자 100개를 수집했어요',         'TOTAL_GRIDS',    '{"value": 100}'),
	('EXPLORER_500',    '탐험가 IV',   '격자 500개를 수집했어요',         'TOTAL_GRIDS',    '{"value": 500}'),
	('RECORDER_10',     '기록러 I',    '영상 10개를 기록했어요',          'UPLOAD_COUNT',   '{"value": 10}'),
	('RECORDER_50',     '기록러 II',   '영상 50개를 기록했어요',          'UPLOAD_COUNT',   '{"value": 50}'),
	('RECORDER_100',    '기록러 III',  '영상 100개를 기록했어요',         'UPLOAD_COUNT',   '{"value": 100}'),
	('REGION_MASTER_10', '동네 입문',  '한 동네의 10%를 채웠어요',        'REGION_PERCENT', '{"value": 10}'),
	('REGION_MASTER_25', '동네 단골',  '한 동네의 25%를 채웠어요',        'REGION_PERCENT', '{"value": 25}'),
	('REGION_MASTER_50', '동네 마스터', '한 동네의 절반을 채웠어요',       'REGION_PERCENT', '{"value": 50}')
ON CONFLICT (code) DO NOTHING;

-- 4) 소급 지급 — notified_at 미기재 = NULL = 다음 조회에서 "새 뱃지" 표시. 판정식은 엔진 findEligible 과 동형
-- TOTAL_GRIDS: 수집 격자 수
INSERT INTO user_badges (user_id, badge_id)
SELECT m.user_id, b.id
FROM (SELECT user_id, COUNT(*) AS metric FROM user_grids GROUP BY user_id) m
JOIN badges b ON b.condition_type = 'TOTAL_GRIDS'
	AND (b.condition_value->>'value')::numeric <= m.metric
ON CONFLICT DO NOTHING;
-- UPLOAD_COUNT: 생애 업로드 수 (삭제된 영상 포함 — 지워도 뱃지 카운트는 줄지 않는다, 비회수 원칙)
INSERT INTO user_badges (user_id, badge_id)
SELECT m.user_id, b.id
FROM (SELECT user_id, COUNT(*) AS metric FROM videos GROUP BY user_id) m
JOIN badges b ON b.condition_type = 'UPLOAD_COUNT'
	AND (b.condition_value->>'value')::numeric <= m.metric
ON CONFLICT DO NOTHING;
-- REGION_PERCENT: 사용자별 최고 행정동 수집률 (region_stats 에 저장돼 있는 값을 그대로 사용 — 재계산 없음)
INSERT INTO user_badges (user_id, badge_id)
SELECT m.user_id, b.id
FROM (SELECT user_id, MAX(progress_rate) AS metric FROM region_stats GROUP BY user_id) m
JOIN badges b ON b.condition_type = 'REGION_PERCENT'
	AND (b.condition_value->>'value')::numeric <= m.metric
ON CONFLICT DO NOTHING;
