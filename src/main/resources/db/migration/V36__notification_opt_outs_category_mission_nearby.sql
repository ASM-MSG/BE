-- MISSION_NEARBY(MSG-418) 설정 카테고리 확장. 기존 행 무변경, CHECK 상위집합 재정의 (V35 형상).
-- 폭 확장 선행: V22 의 VARCHAR(10) 은 14자 MISSION_NEARBY 를 CHECK 평가 전에 길이로 거부한다.
ALTER TABLE notification_opt_outs ALTER COLUMN category TYPE VARCHAR(20);
-- notifications CHECK 는 손대지 않는다. 이 카테고리는 서버 발송 경로가 없어(알림은 기기 로컬 생성)
-- outbox 행이 생기면 결함이고, CHECK 가 그것을 막는 마지막 방어선이다. MODERATION 의 거울상이다
-- (그쪽은 발송만 있고 설정이 없어 opt_outs 에서 의도적 제외됐다, V35 주석).
-- 같은 이유로 notifications.category(V21, VARCHAR(10)) 폭 확장도 불요하다. 이 값은 그 테이블에 실리지 않는다.
ALTER TABLE notification_opt_outs DROP CONSTRAINT chk_notification_opt_outs_category;
ALTER TABLE notification_opt_outs ADD CONSTRAINT chk_notification_opt_outs_category
	CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND', 'VIDEO', 'WEEKLY', 'FRIEND', 'MISSION_NEARBY'));
