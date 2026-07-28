-- V8__zones.sql
-- MSG-234: 격자 표시명 구역(zone). grid_y/grid_x 정수 사각형 — PostGIS 불필요(geospatial 0).
-- 표시명 = name + 행(A=북쪽, max_grid_y-grid_y) + 열(서→동, grid_x-min_grid_x+1). 매칭 없으면 행정동 폴백(§D4).
-- 매칭·명명·검색 bbox 전부 정수 비교/산술이라 조회 경로 geospatial 금지 ADR 을 구조적으로 충족한다(§D1).
-- 인덱스 미추가 — findAll(30~50행)·name ILIKE(seq scan)·정수 매칭 어느 것도 인덱스를 주도로 안 탄다(§D1).
-- 기존 V1~V6 무수정(MSG-130 Flyway CI 가드). V7은 MSG-238 선점 — zones 는 V8.
CREATE TABLE zones (
	id          BIGSERIAL PRIMARY KEY,
	zone_key    VARCHAR(30) NOT NULL UNIQUE,                 -- 안정 식별자·시딩 UPSERT 자연키 — 예: "seomyeon"
	name        VARCHAR(50) NOT NULL,                        -- 사람용 표시명 — 비유일(동명 상권 허용, 예: 전국 "중앙시장"류)
	region_code VARCHAR(10) REFERENCES regions(region_code), -- 소속 행정동(문맥·검색 그룹), nullable
	min_grid_y  INTEGER NOT NULL,
	max_grid_y  INTEGER NOT NULL,
	min_grid_x  INTEGER NOT NULL,
	max_grid_x  INTEGER NOT NULL,
	priority    INTEGER NOT NULL DEFAULT 0,                  -- 겹침 결정성(§D5, priority DESC)

	CONSTRAINT chk_zones_y_range CHECK (min_grid_y <= max_grid_y),
	CONSTRAINT chk_zones_x_range CHECK (min_grid_x <= max_grid_x),
	CONSTRAINT chk_zones_row_cap CHECK (max_grid_y - min_grid_y <= 25)  -- 알파벳 26행(A..Z) = 남북 2.6km 한계
);
COMMENT ON TABLE zones IS '격자 표시명 구역(zone) — 정수 사각형, 좌표 산술 명명. MSG-234';
