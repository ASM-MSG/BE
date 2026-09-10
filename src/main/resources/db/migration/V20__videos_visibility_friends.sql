-- V20__videos_visibility_friends.sql
-- MSG-285: 영상 공개범위 3값째 FRIENDS(친구만 보기) 확장.
--
-- CHECK 제약은 ALTER 로 조건만 바꿀 수 없어 DROP 후 3값으로 재생성한다. 기존 행 데이터는 무변경 —
-- PUBLIC/PRIVATE 는 새 조건도 그대로 만족한다. ADD CONSTRAINT 는 기존 행 전체 검증 스캔이라
-- 그동안 videos 에 잠금이 걸린다 (MVP 데이터 규모에서 순간 잠금 수용).
--
-- ⚠️ 되돌릴 수 없는 마이그레이션: FRIENDS 행이 하나라도 생긴 뒤 구버전(2값 CHECK)으로 롤백하면
-- ADD CONSTRAINT 가 그 행에서 실패한다 — 릴리스 노트에 명시할 것.
--
-- 컬럼(VARCHAR(10))·인덱스는 무변경이다. 'FRIENDS'(7자)는 기존 폭에 들어가고, PUBLIC 한정
-- 부분 인덱스(idx_videos_grid_popular)는 목록 노출이 비목표라 태울 FRIENDS 조회가 없다.

ALTER TABLE videos DROP CONSTRAINT chk_videos_visibility;

ALTER TABLE videos ADD CONSTRAINT chk_videos_visibility
	CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'FRIENDS'));
