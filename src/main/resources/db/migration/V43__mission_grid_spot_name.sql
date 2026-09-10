-- MSG-492: 코스 포토스팟 표시 이름.
-- 저장값은 최종 표시 문자열이다 — 명소 이름("광안리해수욕장")·구역 표시명("서면 A-14")·
-- 행정동 이름("다대2동") 중 무엇이든 완성된 형태로 들어간다. 조회는 이 컬럼을 그대로 통과시킨다.
-- 값은 시더가 채운다(적재 시점 계산, 스펙 D-2). SQL 백필은 없다 — 이름의 출처가 DB 밖
-- 산출물 파일(courses-seed.json)이라 SQL 이 읽을 수 없다.
-- nullable: 코스가 아닌 유형의 스팟과 시더 갱신 전 스팟. 인덱스 없음(검색·정렬에 안 쓴다).
ALTER TABLE mission_grids ADD COLUMN name VARCHAR(100);

COMMENT ON COLUMN mission_grids.name IS '코스 포토스팟 표시 이름 (최종 문자열, MSG-492)';
