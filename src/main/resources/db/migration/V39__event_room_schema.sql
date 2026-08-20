-- V39__event_room_schema.sql
-- MSG-438: 행사방 저장 기반. 시리즈/회차 분리(FR-EVENT-07), 격자 영역과 대표 격자(FR-EVENT-08).
-- 격자 컬럼은 grids 테이블을 FK 하지 않는다(lazy insert 전략상 행 존재가 보장되지 않음).

CREATE TABLE event_series (
	id         BIGSERIAL PRIMARY KEY,
	series_key VARCHAR(50) NOT NULL UNIQUE,  -- 시딩 UPSERT 자연키, 예: "biff"
	name       VARCHAR(100) NOT NULL
);
COMMENT ON TABLE event_series IS '행사 시리즈 — 반복 개최되는 행사의 공통 단위. MSG-438';

CREATE TABLE event_occurrences (
	id              BIGSERIAL PRIMARY KEY,
	event_series_id BIGINT NOT NULL REFERENCES event_series(id),
	occurrence_key  VARCHAR(60) NOT NULL UNIQUE,  -- 자연키, 예: "biff-busan-2026"
	title           VARCHAR(100) NOT NULL,
	city_name       VARCHAR(30) NOT NULL,   -- 시 칩 묶음 기준 라벨, 예: "부산" (FR-EVENT-01)
	starts_at       TIMESTAMP NOT NULL,     -- UTC (전 테이블 공통, MSG-376 체계)
	ends_at         TIMESTAMP NOT NULL,
	visible_from    TIMESTAMP NOT NULL,     -- 칩 노출 시작 = starts_at - 14일 (파생 고정, 시더 계산)
	min_grid_y      INTEGER NOT NULL,       -- 노출 영역 정수 사각형(뷰포트 겹침 판정 재료, zones 선례)
	max_grid_y      INTEGER NOT NULL,
	min_grid_x      INTEGER NOT NULL,
	max_grid_x      INTEGER NOT NULL,

	CONSTRAINT chk_event_occ_period    CHECK (starts_at < ends_at),
	-- 파생 규칙을 DDL로 고정: 어떤 쓰기 경로도 2주 전 정책을 우회하지 못한다 (2026-08-20 확정)
	CONSTRAINT chk_event_occ_visible   CHECK (visible_from = starts_at - INTERVAL '14 days'),
	CONSTRAINT chk_event_occ_y_range   CHECK (min_grid_y <= max_grid_y),
	CONSTRAINT chk_event_occ_x_range   CHECK (min_grid_x <= max_grid_x)
);
COMMENT ON TABLE event_occurrences IS '행사 회차 — 특정 기간에 열린 개최 단위. MSG-438';

CREATE TABLE event_locations (
	id                     BIGSERIAL PRIMARY KEY,
	event_occurrence_id    BIGINT NOT NULL REFERENCES event_occurrences(id),
	location_key           VARCHAR(60) NOT NULL UNIQUE,  -- 자연키, 예: "biff-2026-cinema-center"
	name                   VARCHAR(100) NOT NULL,        -- "영화의전당"
	type                   VARCHAR(20) NOT NULL,         -- EventLocationType enum 문자열
	operating_hours        VARCHAR(100),                 -- 표시용 문자열, 없는 위치(퍼레이드) NULL
	display_order          INTEGER NOT NULL DEFAULT 0,   -- 위치 목록 정렬 1차 키(439 계약)
	representative_grid_id VARCHAR(20) NOT NULL,

	CONSTRAINT uq_event_locations_id_occ UNIQUE (id, event_occurrence_id)  -- 비정규화 FK 대상
);
COMMENT ON TABLE event_locations IS '행사 위치 — 회차에 속한 실제 장소, 영상은 대표 격자 하나에만 붙는다. MSG-438';

CREATE TABLE event_location_grids (
	event_location_id   BIGINT NOT NULL,
	event_occurrence_id BIGINT NOT NULL,     -- 비정규화: 회차 내 격자 유일성 제약 재료
	grid_id             VARCHAR(20) NOT NULL,

	PRIMARY KEY (event_location_id, grid_id),
	-- 한 격자는 한 회차 안에서 위치 하나에만 귀속(439 계약 3). 행 단위 저장이라 EXCLUDE 불요.
	-- DEFERRABLE: 격자를 위치 A에서 B로 옮기는 재시드에서 Hibernate flush 가 insert 를 delete 보다
	-- 먼저 실행하므로, 즉시 제약이면 순서 통제가 필요해진다. 커밋 시점 검증으로 순서 의존을 없앤다.
	CONSTRAINT uq_event_grid_per_occ UNIQUE (event_occurrence_id, grid_id)
		DEFERRABLE INITIALLY DEFERRED,
	-- 비정규화 정합: 격자 행의 회차 = 소속 위치의 회차임을 DDL이 보장
	CONSTRAINT fk_event_grid_location FOREIGN KEY (event_location_id, event_occurrence_id)
		REFERENCES event_locations(id, event_occurrence_id) ON DELETE CASCADE
);
COMMENT ON TABLE event_location_grids IS '행사 위치의 격자 영역 — 어느 격자를 눌러도 같은 위치로 해석된다. MSG-438';

-- 대표 격자는 반드시 영역 소속(FR-3). 순환 참조라 지연 제약: 같은 트랜잭션 안에서
-- 위치 insert 후 격자 insert 가 가능하고, 커밋 시점에 소속이 검증된다.
ALTER TABLE event_locations
	ADD CONSTRAINT fk_event_loc_rep_grid FOREIGN KEY (id, representative_grid_id)
		REFERENCES event_location_grids(event_location_id, grid_id)
		DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE event_videos (
	video_id            BIGINT PRIMARY KEY REFERENCES videos(id) ON DELETE CASCADE,
	event_occurrence_id BIGINT NOT NULL,
	event_location_id   BIGINT NOT NULL,

	-- 영상의 회차 = 소속 위치의 회차(FR-EVENT-07 미혼합의 쓰기 시점 보장)
	CONSTRAINT fk_event_video_location FOREIGN KEY (event_location_id, event_occurrence_id)
		REFERENCES event_locations(id, event_occurrence_id)
);
COMMENT ON TABLE event_videos IS '행사 영상 연결 — videos 행의 1:1 확장(독립 파일 컬럼 없음). MSG-438';

-- 위치별 count(439)와 피드(440)의 조회 키. 사용자 콘텐츠라 증가 테이블이므로 인덱스를 단다.
CREATE INDEX idx_event_videos_location ON event_videos(event_location_id);
