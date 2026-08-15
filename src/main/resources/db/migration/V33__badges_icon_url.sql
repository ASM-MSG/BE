-- ============================================================================
-- FillMap V33: 현역 뱃지 23종 icon_url 채움 (MSG-405)
-- 피그마 medal 에셋을 PNG(@2x, 296x296)로 뽑아 dev 버킷 badges/ 프리픽스에 업로드한 URL.
-- URL 은 dev 버킷 하드코딩 — 레포에 Flyway placeholder 선례가 없어 설정 체계를 새로 안 만든다.
-- prod 버킷 신설 시 이 URL 들을 UPDATE 하는 마이그레이션이 필요하다 (prod 앱 미가동, MSG-405).
-- 은퇴 뱃지 3종(MISSION_1·5·10)은 대상 목록에 없어 icon_url NULL 유지 (신규 노출 없음).
-- 파일명 = 뱃지 code 라서 URL 은 code 로 조립한다.
-- V1~V32 무수정(checksum).
-- ============================================================================

UPDATE badges
SET icon_url = 'https://fillmap-video-dev.s3.ap-northeast-2.amazonaws.com/badges/' || code || '.png'
WHERE code IN (
	'EXPLORER_1', 'EXPLORER_10', 'EXPLORER_50', 'EXPLORER_100', 'EXPLORER_500',
	'RECORDER_10', 'RECORDER_50', 'RECORDER_100',
	'REGION_MASTER_10', 'REGION_MASTER_25', 'REGION_MASTER_50',
	'STREAK_3', 'STREAK_7', 'STREAK_30',
	'EVENT_1', 'EVENT_3', 'EVENT_10',
	'COURSE_1', 'COURSE_3', 'COURSE_10',
	'POPUP_1', 'POPUP_3', 'POPUP_10'
);
