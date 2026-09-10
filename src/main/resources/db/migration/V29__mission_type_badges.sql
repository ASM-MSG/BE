-- ============================================================================
-- FillMap V29: 미션 뱃지 종류별 재편 (MSG-363)
-- 1) condition_type CHECK 확장 (V9 1번 블록 복제)
-- 2) badges.retired_at 추가. 지급 후보에서만 빼고 획득 이력은 보존
-- 3) 종류별 뱃지 9종 시딩 (임계값 1, 3, 10)
-- 4) 합산 뱃지 3종 은퇴
-- 5) 소급 지급 (V9 4번 블록 패턴, ON CONFLICT 멱등)
-- V1~V28 무수정(checksum).
-- ============================================================================

-- 1) CHECK 확장
ALTER TABLE badges DROP CONSTRAINT chk_badges_condition;
ALTER TABLE badges ADD CONSTRAINT chk_badges_condition CHECK (condition_type IN
	('REGION_PERCENT', 'TOTAL_GRIDS', 'STREAK_DAYS', 'UPLOAD_COUNT', 'MISSION_COUNT',
	 'EVENT_COUNT', 'COURSE_COUNT', 'POPUP_COUNT', 'SPECIAL'));

-- 2) 은퇴 표시 (NULL = 현역). 판정 쿼리 3개가 이 컬럼으로 후보를 거른다
ALTER TABLE badges ADD COLUMN retired_at TIMESTAMP;

-- 3) 시딩. 목록 순서 = badges.id 오름차순이라 축 단위로 티어 오름차순 삽입 (MSG-201 D6)
INSERT INTO badges (code, name, description, condition_type, condition_value) VALUES
	('EVENT_1',   '축제 입문',   '축제 1곳을 다녀왔어요',    'EVENT_COUNT',  '{"value": 1}'),
	('EVENT_3',   '축제 단골',   '축제 3곳을 다녀왔어요',    'EVENT_COUNT',  '{"value": 3}'),
	('EVENT_10',  '축제 마스터', '축제 10곳을 다녀왔어요',   'EVENT_COUNT',  '{"value": 10}'),
	('COURSE_1',  '코스 입문',   '코스 1개를 기록했어요',    'COURSE_COUNT', '{"value": 1}'),
	('COURSE_3',  '코스 단골',   '코스 3개를 기록했어요',    'COURSE_COUNT', '{"value": 3}'),
	('COURSE_10', '코스 마스터', '코스 10개를 기록했어요',   'COURSE_COUNT', '{"value": 10}'),
	('POPUP_1',   '팝업 입문',   '팝업 1곳을 다녀왔어요',    'POPUP_COUNT',  '{"value": 1}'),
	('POPUP_3',   '팝업 단골',   '팝업 3곳을 다녀왔어요',    'POPUP_COUNT',  '{"value": 3}'),
	('POPUP_10',  '팝업 마스터', '팝업 10곳을 다녀왔어요',   'POPUP_COUNT',  '{"value": 10}')
ON CONFLICT (code) DO NOTHING;

-- 4) 합산 뱃지 은퇴. DELETE 금지: user_badges FK 가 막고, 지워지면 획득 이력이 사라진다(FR-4).
--    AT TIME ZONE 'UTC' 는 저장 규칙 통일용 (MSG-314 D4). 이 값은 NULL 여부로만 읽는다.
UPDATE badges
SET retired_at = now() AT TIME ZONE 'UTC'
WHERE code IN ('MISSION_1', 'MISSION_5', 'MISSION_10')
  AND retired_at IS NULL;

-- 5) 소급 지급 (FR-5). notified_at 미기재 = NULL = 다음 조회에서 "새 뱃지" 표시 (V9 4번 블록과 동일).
--    b.condition_type = m.type || '_COUNT' 가 유형에서 축으로 가는 명명 규칙 그 자체라 축마다
--    블록을 복제하지 않는다. AREA, THEME, CONTINUOUS 는 WHERE 에서 빠져 대상이 아니다(FR-6).
INSERT INTO user_badges (user_id, badge_id)
SELECT m.user_id, b.id
FROM (
	SELECT um.user_id, mi.type, COUNT(*) AS metric
	FROM user_missions um
	JOIN missions mi ON mi.id = um.mission_id
	WHERE mi.type IN ('EVENT', 'COURSE', 'POPUP')
	GROUP BY um.user_id, mi.type
) m
JOIN badges b ON b.condition_type = m.type || '_COUNT'
	AND (b.condition_value->>'value')::numeric <= m.metric
ON CONFLICT DO NOTHING;
