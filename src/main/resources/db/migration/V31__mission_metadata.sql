-- V31__mission_metadata.sql
-- MSG-383: 화면이 읽을 미션 메타데이터 컬럼 8개를 missions 에 더한다.
--
-- MSG-224 · MSG-225 · MSG-235 가 원본의 설명·장소·운영시간·거리를 "missions 에 컬럼이 없어 미적재"로
-- 남겼던 결정을 대체한다 (PRD mission-map-explore FR-11~FR-14, SRS FR-MISSION-16). 값은 시더
-- 재실행이 채운다 — 백필 SQL 을 두지 않는 이유는 값의 출처를 원본 파일 한 곳으로 유지하기 위해서다(§D6).
--
-- 전 컬럼 nullable · DEFAULT 없음 → 기존 행은 NULL 로 남고 테이블 재작성이 없다(카탈로그 변경만).
-- image_url 에는 우리 스토리지(S3) URL 만 넣는다 — 외부 도메인 주소 금지(§D7, users.profile_image_url 규칙).
-- 이 티켓 범위에서 값을 쓰는 경로는 없다. 인덱스는 만들지 않는다(이 컬럼으로 검색·정렬하는 요구 없음).

ALTER TABLE missions
	ADD COLUMN description      TEXT,
	ADD COLUMN place_name       VARCHAR(200),
	ADD COLUMN source_url       TEXT,
	ADD COLUMN operation_time   TEXT,
	ADD COLUMN image_url        TEXT,
	ADD COLUMN distance_meters  INTEGER,
	ADD COLUMN duration_minutes INTEGER,
	ADD COLUMN difficulty       SMALLINT;

-- 코스 전용 3개 — chk_missions_path 와 같은 형태. 축제 행에 거리가 붙어 "거리 22km 인 축제"가 화면에
-- 뜨는 사고를 스키마가 막는다. 값 범위(난이도 1~3 · 거리 양수)는 걸지 않는다 — 등급이 하나 늘 때마다
-- 마이그레이션이 필요해지고, 형식 검증은 시더 reader 의 몫이다(§D2).
ALTER TABLE missions ADD CONSTRAINT chk_missions_course_metrics CHECK (
	type = 'COURSE'
	OR (distance_meters IS NULL AND duration_minutes IS NULL AND difficulty IS NULL)
);
