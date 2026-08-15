-- ============================================================================
-- FillMap V33: 뱃지 26종 icon_url 채움 + NOT NULL (MSG-405, 스펙 MSG-363 D6·FR-7)
-- 피그마 medal 에셋을 PNG(@2x, 296x296)로 뽑아 정적 에셋 버킷(fillmap-static)에 업로드한 URL.
-- 저장 위치·경로 규칙은 MSG-363 스펙 D6 확정: 환경 공용 정적 버킷 + badges/{code 소문자}.{확장자},
-- 절대 URL 저장. 환경 공용이라 dev/prod 어디서 실행돼도 이 URL 이 옳다.
-- 확장자는 D6 예시(svg)와 달리 png — SVG 는 숫자 텍스트가 기기 폰트를 타서 래스터로 확정 (MSG-405).
-- 파일명 = 뱃지 code 소문자라서 URL 은 lower(code) 로 조립한다 (D6 규칙 그대로).
-- 은퇴 3종(MISSION_1·5·10)은 획득자에게 계속 보이므로 그림이 필요하되, 신규 획득자가 없어
-- 셋이 같은 그림을 공유해도 무방하다(스펙 D6) — 공유 회색 메달 mission_legacy.png 로 채운다.
-- 전 행을 채운 뒤 NOT NULL 을 걸어 그림 없는 뱃지는 시딩 자체가 실패하게 한다(스펙 FR-7).
-- V1~V32 무수정(checksum).
-- ============================================================================

UPDATE badges
SET icon_url = 'https://fillmap-static.s3.ap-northeast-2.amazonaws.com/badges/' || lower(code) || '.png'
WHERE code IN (
	'EXPLORER_1', 'EXPLORER_10', 'EXPLORER_50', 'EXPLORER_100', 'EXPLORER_500',
	'RECORDER_10', 'RECORDER_50', 'RECORDER_100',
	'REGION_MASTER_10', 'REGION_MASTER_25', 'REGION_MASTER_50',
	'STREAK_3', 'STREAK_7', 'STREAK_30',
	'EVENT_1', 'EVENT_3', 'EVENT_10',
	'COURSE_1', 'COURSE_3', 'COURSE_10',
	'POPUP_1', 'POPUP_3', 'POPUP_10'
);

-- 은퇴 3종 — 공유 레거시 메달
UPDATE badges
SET icon_url = 'https://fillmap-static.s3.ap-northeast-2.amazonaws.com/badges/mission_legacy.png'
WHERE code IN ('MISSION_1', 'MISSION_5', 'MISSION_10');

-- 이후로 그림 없는 뱃지는 시딩 자체가 실패한다(FR-7). 회색 칸이 운영에 새는 것을 DB 가 막는다.
ALTER TABLE badges ALTER COLUMN icon_url SET NOT NULL;
