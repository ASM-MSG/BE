-- V42__mission_representative_grid.sql
-- MSG-459: 축제·팝업 미션의 대표 격자 (PRD FR-11~14).
-- 미션 경유 업로드(POST /api/missions/{missionId}/videos)가 영상을 붙일 격자 한 칸을 미션마다
-- 하나 정해 둔다. 지금은 축제 판정 범위가 9×9(81칸)라 같은 축제 영상이 81곳으로 흩어진다.
-- 값이 있다는 것이 곧 "미션 경유 업로드 대상"이라는 뜻이라 그 전제를 아래 CHECK 로 묶는다.

ALTER TABLE missions ADD COLUMN representative_grid_id VARCHAR(20);
COMMENT ON COLUMN missions.representative_grid_id IS
	'미션 경유 업로드가 영상을 붙일 격자. 축제·팝업만 값을 갖는다. MSG-459';

-- target_count = 1 이 조건에 들어간 이유(스펙 D-9): 영상 한 건이 곧 스탬프여야 종료 미션 보존이
-- 성립하는데(스탬프 없는 종료 미션은 정리에 지워진다), 목표가 2 이상이면 그 고리가 끊긴다.
ALTER TABLE missions
	ADD CONSTRAINT chk_missions_rep_grid_type
	CHECK (representative_grid_id IS NULL OR (type IN ('EVENT', 'POPUP') AND target_count = 1));

-- 기존 축제·팝업 백필. 산출 규칙(RepresentativeGridResolver)과 결과가 같다고 증명할 수 있는
-- 형태만 채우고 나머지는 NULL 로 남긴다(스펙 D-2) — 꽉 찬 직사각형이고 두 변이 모두 홀수면
-- 정중앙(리졸버 1단과 같은 식), 두 변이 모두 2 이하이면 무게중심 동률이라 남서 칸(리졸버 3단).
-- 이 둘이 축제 81칸과 팝업 1·2·4칸을 전부 덮는다. 조건은 위 CHECK 와 같은 집합이어야 한다.
UPDATE missions m
SET representative_grid_id = b.grid_id
FROM (
	SELECT c.mission_id,
		CASE
			WHEN (MAX(c.gy) - MIN(c.gy)) % 2 = 0 AND (MAX(c.gx) - MIN(c.gx)) % 2 = 0
				THEN ((MIN(c.gy) + MAX(c.gy)) / 2) || '_' || ((MIN(c.gx) + MAX(c.gx)) / 2)
			ELSE MIN(c.gy) || '_' || MIN(c.gx)
		END AS grid_id
	FROM (
		SELECT mission_id,
			split_part(grid_id, '_', 1)::BIGINT AS gy,
			split_part(grid_id, '_', 2)::BIGINT AS gx
		FROM mission_grids
	) c
	GROUP BY c.mission_id
	HAVING COUNT(*) = (MAX(c.gy) - MIN(c.gy) + 1) * (MAX(c.gx) - MIN(c.gx) + 1)
		AND (
			((MAX(c.gy) - MIN(c.gy)) % 2 = 0 AND (MAX(c.gx) - MIN(c.gx)) % 2 = 0)
			OR (MAX(c.gy) - MIN(c.gy) <= 1 AND MAX(c.gx) - MIN(c.gx) <= 1)
		)
) b
WHERE m.id = b.mission_id
  AND m.type IN ('EVENT', 'POPUP')
  AND m.target_count = 1;

-- 대표 격자는 반드시 판정 격자 집합 소속(PRD 비기능 "저장 계층이 보장").
-- mission_grids 의 PK 가 (mission_id, grid_id) 라 복합 FK 로 참조할 수 있다.
-- 지연 제약인 이유: 두 테이블이 서로를 참조해, 같은 트랜잭션에서 미션을 먼저 넣고 격자를 넣어도
-- (팝업 격자를 갈아 끼우고 대표 격자를 나중에 고쳐도) 커밋 시점에 한 번만 검증되므로 쓰기 순서를
-- 통제할 필요가 없다. ON DELETE 를 적지 않은 것은 의도다 — 미션을 지우면 mission_grids 가
-- CASCADE 로 함께 지워지고 참조하던 missions 행도 사라져 커밋 시점에 검증할 것이 남지 않는다
-- (종료 미션 정리 deleteEndedBySourceWithoutStamps 가 이 경로다).
ALTER TABLE missions
	ADD CONSTRAINT fk_missions_rep_grid FOREIGN KEY (id, representative_grid_id)
		REFERENCES mission_grids(mission_id, grid_id)
		DEFERRABLE INITIALLY DEFERRED;

-- 격자 역조회(FR-15)의 접근 경로. 값 있는 행만 담아 코스와 미백필 행을 인덱스에서 뺀다.
CREATE INDEX idx_missions_representative_grid ON missions (representative_grid_id)
	WHERE representative_grid_id IS NOT NULL;
