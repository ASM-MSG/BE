-- ============================================================================
-- V14: missions type=POPUP + source_key — 팝업 미션 적재 (MSG-235 D3, PRD FR-3·7)
--
-- ① 지도 홈 칩 4종 확정(2026-07-25)으로 팝업은 EVENT 공유가 아니라 별도 타입이다
--    (2026-07-31 성민 확정) — FE 가 type 으로 팝업 스토어 칩을 분기한다.
-- ② source_key = 외부 안정 id 멱등 키(팝가 id). NULL = 외부 id 없는 적재(수동·축제·코스).
-- ③ 부분 유니크 인덱스 = 앱 dedupe 의 DB 백스톱 — NULL 은 인덱스 밖이라 무영향.
-- ============================================================================

-- ① type CHECK에 'POPUP' 추가 (FR-7)
ALTER TABLE missions DROP CONSTRAINT chk_missions_type;
ALTER TABLE missions ADD CONSTRAINT chk_missions_type CHECK (type IN
	('COURSE', 'AREA', 'EVENT', 'THEME', 'CONTINUOUS', 'POPUP'));

-- ② 외부 안정 id 멱등 키 (FR-3, D3). NULL = 외부 id 없는 적재(수동·축제·코스)
ALTER TABLE missions ADD COLUMN source_key VARCHAR(30);

-- ③ 앱 dedupe의 DB 백스톱 — NULL 은 인덱스 밖
CREATE UNIQUE INDEX uq_missions_source_key ON missions (source, source_key)
	WHERE source_key IS NOT NULL;
