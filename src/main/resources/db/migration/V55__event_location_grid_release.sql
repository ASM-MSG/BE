-- 노출 중지가 격자 클레임을 반납하게 한다 (MSG-598).
--
-- 중지는 event_locations.hidden_at 만 찍고 event_location_grids 행을 남겨 뒀다. 그런데
-- uq_event_grid_per_occ(회차+격자 UNIQUE)는 숨김 여부를 보지 않으므로, 중지된 위치가 차지한
-- 칸은 같은 회차의 새 승인에게 영구히 막힌 자리가 된다 (승인이 13452 로 거부).
--
-- 칸 행을 지우려면 대표 격자 FK(fk_event_loc_rep_grid)가 가리킬 대상이 없어야 하므로
-- representative_grid_id 의 NOT NULL 을 풀고, "노출 중인 위치는 대표 격자를 갖는다"는
-- 불변식만 CHECK 로 남긴다. 숨긴 위치의 영역 기록은 신청 원본(event_submission_area_rects)에
-- 그대로 남아 있어 잃는 정보가 없다.
ALTER TABLE event_locations ALTER COLUMN representative_grid_id DROP NOT NULL;

ALTER TABLE event_locations
	ADD CONSTRAINT chk_event_loc_rep_grid_visible
		CHECK (hidden_at IS NOT NULL OR representative_grid_id IS NOT NULL);

COMMENT ON COLUMN event_locations.representative_grid_id IS
	'영상이 붙는 격자 하나. 노출 중지로 격자 클레임을 반납한 위치만 NULL. MSG-598';

-- 이미 중지돼 자리를 막고 있는 위치의 클레임을 소급 반납한다. 대표 격자를 먼저 비우는 것은
-- 지연 FK 가 커밋 시점에 검증되긴 해도 순서를 읽는 사람이 헷갈리지 않게 하기 위해서다.
UPDATE event_locations SET representative_grid_id = NULL WHERE hidden_at IS NOT NULL;

DELETE FROM event_location_grids g
	USING event_locations l
	WHERE g.event_location_id = l.id AND l.hidden_at IS NOT NULL;
