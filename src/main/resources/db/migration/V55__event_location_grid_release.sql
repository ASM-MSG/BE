-- 노출 중지가 격자 클레임을 반납하게 한다 (MSG-598).
--
-- 중지는 event_locations.hidden_at 만 찍고 event_location_grids 행을 남겨 뒀다. 그런데
-- uq_event_grid_per_occ(회차+격자 UNIQUE)는 숨김 여부를 보지 않으므로, 중지된 위치가 차지한
-- 칸은 같은 회차의 새 승인에게 영구히 막힌 자리가 된다 (승인이 13452 로 거부).
--
-- 칸 행을 지우려면 대표 격자 FK(fk_event_loc_rep_grid)가 가리킬 대상이 없어야 하므로
-- representative_grid_id 의 NOT NULL 을 풀고, 대신 "노출 중이면 대표 격자가 있고 중지됐으면
-- 없다"는 등식을 CHECK 로 세운다. 숨긴 위치의 영역 기록은 신청 원본
-- (event_submission_location_rects)에 그대로 남아 있어 잃는 정보가 없다.
ALTER TABLE event_locations ALTER COLUMN representative_grid_id DROP NOT NULL;

COMMENT ON COLUMN event_locations.representative_grid_id IS
	'영상이 붙는 격자 하나. 노출 중지로 격자 클레임을 반납한 위치만 NULL. MSG-598';

-- 이미 중지돼 자리를 막고 있는 위치의 클레임을 소급 반납한다. CHECK 보다 먼저 도는 것이
-- 계약이다 — 이 UPDATE 전에는 (숨김 + 대표 격자 있음) 행이 남아 있어 CHECK 가 통과하지 못한다.
UPDATE event_locations SET representative_grid_id = NULL WHERE hidden_at IS NOT NULL;

DELETE FROM event_location_grids g
	USING event_locations l
	WHERE g.event_location_id = l.id AND l.hidden_at IS NOT NULL;

-- 등식(양방향)으로 세우는 이유: 한쪽만(노출 중이면 대표 격자 있음) 걸면 "숨김 + 대표 격자
-- 있음" 상태가 허용돼, 이 마이그레이션이 적용된 DB 에 구버전 코드가 붙는 창(롤백·롤링 배포)
-- 에서 중지가 다시 칸을 남길 수 있다. 그 상태는 조용히 막힌 자리가 되고 V55 는 이미 돌아
-- 다시 정리해 주지 않는다. 등식이면 구버전의 중지 UPDATE 가 DB 에서 거부돼(트랜잭션 전체
-- 롤백, 중지 기록도 안 남는다) 관리자가 재시도로 신버전 인스턴스에 걸 수 있다.
ALTER TABLE event_locations
	ADD CONSTRAINT chk_event_loc_rep_grid_visible
		CHECK ((hidden_at IS NULL) = (representative_grid_id IS NOT NULL));
