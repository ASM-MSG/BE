-- V5__grids_region_code.sql
-- MSG-167: grids.region_code — 격자 중심점이 속한 행정동 라벨.
-- 쓰기 시 1회 판정(VideoRepository.upsertGrid 인라인, 격자 생애 1회) + 조회는 순수 equi-join.
-- 판정 규칙은 RegionRepository.findContainingRegion(MSG-93)·refreshRegionStats(MSG-155)와 동일하다:
--   ST_Covers(boundary_geom, 격자 중심점) ORDER BY region_code LIMIT 1
-- 저장값이 by-grid 귀속·recompute 귀속과 항상 같은 행정동이라 세 경로가 어긋나지 않는다(§D1).
-- 인덱스 미추가(§D5 — region_code 주도 조회 없음). Grid 엔티티 미매핑(§D7 — 접근 전부 native).
-- nullable — 무귀속(해안)은 NULL, 판정값은 항상 실존 region_code(FK). 기존 V1~V4 무수정(MSG-130).

ALTER TABLE grids ADD COLUMN region_code VARCHAR(10) REFERENCES regions(region_code);
COMMENT ON COLUMN grids.region_code IS '격자 중심점이 속한 행정동(쓰기 시 1회 판정, 무귀속=NULL) — MSG-167';

-- 멱등 백필: region_code IS NULL 행만 저장된 center_geom 으로 판정한다(upsertGrid 인라인과 같은 점·같은 규칙).
-- IS NULL 가드라 재실행(flyway clean 후)해도 같은 값으로 수렴하고, 해안(무귀속) 격자는 재판정해도 NULL 로
-- 안정된다 — NULL 의 두 의미("해안" vs "미판정")가 재판정 시 동치라 무해하다(§D2).
-- regions 미시딩 환경은 격자도 없어 no-op → 이후 신규 격자는 upsertGrid 인라인이 채운다.
UPDATE grids g SET region_code = (
	SELECT r.region_code FROM regions r
	WHERE ST_Covers(r.boundary_geom, g.center_geom)
	ORDER BY r.region_code
	LIMIT 1)
WHERE g.region_code IS NULL;
