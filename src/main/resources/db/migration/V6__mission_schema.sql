-- ============================================================================
-- FillMap V6 — Mission Schema (MSG-166)
-- missions · mission_grids · user_missions.
--
-- 2026-07-20 설계검토 + 2026-07-23 후속결정(반영구 시드 · 코스 target=포토스팟 5~8곳) 반영.
-- 미션 5종(COURSE·AREA·EVENT·THEME·CONTINUOUS)을 테이블 3개 + 단일 판정 쿼리로 흡수한다
-- (유형별 전략 클래스 금지 — 쿼리 분기만).
-- 순수 스키마 추가 — 계약 인터페이스 무변경.
-- MSG-145(V3 AI 컬럼)과 분리된 별도 버전. 기존 V1~V5 무수정(MSG-130 checksum).
--
-- ⚠️ MVP 범위(5종 중 어디까지) 팀 확정 전 머지 금지 — 개발·PR까지만.
-- ============================================================================

-- ============================================================================
-- missions (미션 정의 — 5종: COURSE·AREA·EVENT·THEME·CONTINUOUS)
-- 코스는 무기간(상시): start_at/end_at NULL 허용. reward 컬럼 없음(MVP 보상=스탬프뿐, §D6).
-- path 는 코스 표시용 GeoJSON LineString — 판정 아닌 표시용이라 GEOMETRY 아닌 JSONB(§D1).
-- ============================================================================
CREATE TABLE missions (
	id           BIGSERIAL PRIMARY KEY,
	type         VARCHAR(20)  NOT NULL,
	title        VARCHAR(200) NOT NULL,
	region_code  VARCHAR(10)  REFERENCES regions(region_code),   -- 구역(AREA) 등 행정동 귀속, 그 외 NULL
	start_at     TIMESTAMP,                                      -- NULL = 무기간(상시, 코스)
	end_at       TIMESTAMP,                                      -- NULL = 무기간(상시, 코스)
	-- 완료에 필요한 distinct 방문 격자 수 (이벤트=1, 코스=포토스팟 일부/전부)
	target_count INTEGER      NOT NULL,
	-- 코스 표시용 GeoJSON LineString, 코스 외 NULL (§D1)
	path         JSONB,
	created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

	CONSTRAINT chk_missions_type   CHECK (type IN
		('COURSE', 'AREA', 'EVENT', 'THEME', 'CONTINUOUS')),
	CONSTRAINT chk_missions_target CHECK (target_count > 0),
	CONSTRAINT chk_missions_period CHECK (start_at IS NULL OR end_at IS NULL OR start_at <= end_at),
	CONSTRAINT chk_missions_path   CHECK (path IS NULL OR type = 'COURSE')
);

-- ============================================================================
-- mission_grids (미션 판정 대상 격자 — 코스는 포토스팟 5~8곳만, 전체 격자 아님)
-- grid_id 는 grids FK 없음: grids 는 lazy insert 라 미방문 포토스팟은 grids row 부재 가능(§D2).
-- 좌표에서 계산되는 논리 식별자("{grid_y}_{grid_x}")라 grids row 없이도 유효하다.
-- ============================================================================
CREATE TABLE mission_grids (
	mission_id BIGINT      NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
	-- 논리 참조("{grid_y}_{grid_x}") — grids FK 없음(§D2)
	grid_id    VARCHAR(20) NOT NULL,
	-- 코스 포토스팟 순번(1..N), 순서 없는 유형은 NULL
	seq        INTEGER,

	PRIMARY KEY (mission_id, grid_id)
);
CREATE INDEX idx_mission_grids_grid ON mission_grids (grid_id);   -- 역방향: grid_id → 미션 (판정, §D4)

-- ============================================================================
-- user_missions (스탬프 — 비회수·영속)
-- 미션 만료 후 videos 재계산으로 복원 불가라 영속 필요.
-- user_badges 패턴 미러(복합 PK · user CASCADE · mission NO ACTION · 단일 타임스탬프, §D7).
-- ============================================================================
CREATE TABLE user_missions (
	user_id      BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	-- NO ACTION: 스탬프 걸린 미션 하드삭제 차단(비회수 보호)
	mission_id   BIGINT    NOT NULL REFERENCES missions(id),
	completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

	PRIMARY KEY (user_id, mission_id)
);
