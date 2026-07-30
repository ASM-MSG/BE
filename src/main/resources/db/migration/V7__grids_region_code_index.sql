-- V7__grids_region_code_index.sql
-- MSG-238: 행정동 축 전역 탐색 — grids.region_code 주도 조회(WHERE g.region_code = :regionCode) 최초 등장.
-- MSG-167 §D5가 예약한 조건("region_code 주도 조회가 생기면 그때 추가")의 발동이다 — 이름도 예약 그대로.
-- 단순 btree 하나, 컬럼·데이터 변경 없음. partial(WHERE region_code IS NOT NULL)은 기각(§D6):
-- NULL 행은 equi 조건에 어차피 안 잡혀 절감 대상이 무라벨(해안) 격자뿐이라 실익이 없고,
-- 단순 인덱스가 이후 소비자(핫존 등)에도 무조건 안전하다. 기존 V1~V6 무수정(MSG-130 checksum).

CREATE INDEX idx_grids_region_code ON grids (region_code);
