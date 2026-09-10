package com.msg.fillmap.region.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.region.repository.RegionRepository;

// 검증: FR-REGION-01
@SpringBootTest
@Transactional
@DisplayName("RegionRepository native UPSERT (실 PostGIS) — 합성 코드·롤백 격리")
class RegionRepositoryTest {

	// 서울 인근 소형 직사각형. ST_Multi 정규화·유효성 검증용(면적 값 자체는 무관).
	private static final String POLYGON = rectanglePolygonJson(127.0, 37.5, 127.001, 37.501);

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	private int upsert(String code, String name, String geometryJson) {
		return regionRepository.upsert(code, name, code.substring(0, 5), geometryJson, CELL_AREA_M2);
	}

	private Object scalar(String sql, String code) {
		return em.createNativeQuery(sql).setParameter("code", code).getSingleResult();
	}

	@Test
	@DisplayName("행정동 한 건을 UPSERT 하면 boundary_geom 이 NOT NULL 로 저장된다")
	void 행정동_한건을_UPSERT하면_boundary_geom이_NOT_NULL로_저장된다() {
		String code = syntheticCode("1");

		assertThat(upsert(code, "합성동", POLYGON)).isEqualTo(1);
		em.flush();

		Boolean isNull = (Boolean) scalar(
			"SELECT boundary_geom IS NULL FROM regions WHERE region_code = :code", code);
		assertThat(isNull).isFalse();
	}

	@Test
	@DisplayName("저장된 geometry 는 유효한 MultiPolygon 이다 (ST_IsValid · ST_GeometryType)")
	void 저장된_geometry는_유효한_MultiPolygon이다() {
		String code = syntheticCode("2");
		upsert(code, "합성동", POLYGON);
		em.flush();

		Boolean valid = (Boolean) scalar(
			"SELECT ST_IsValid(boundary_geom::geometry) FROM regions WHERE region_code = :code", code);
		String type = (String) scalar(
			"SELECT ST_GeometryType(boundary_geom::geometry) FROM regions WHERE region_code = :code", code);

		assertThat(valid).isTrue();
		assertThat(type).isEqualTo("ST_MultiPolygon");
	}

	@Test
	@DisplayName("같은 region_code 로 다시 UPSERT 하면 row 가 늘지 않고 값이 갱신된다 (멱등)")
	void 같은_region_code로_다시_UPSERT하면_row가_늘지않고_값이_갱신된다() {
		String code = syntheticCode("3");
		upsert(code, "옛이름동", POLYGON);
		upsert(code, "새이름동", POLYGON);
		em.flush();

		Number count = (Number) scalar(
			"SELECT COUNT(*) FROM regions WHERE region_code = :code", code);
		String name = (String) scalar(
			"SELECT region_name FROM regions WHERE region_code = :code", code);

		assertThat(count.longValue()).isEqualTo(1L);
		assertThat(name).isEqualTo("새이름동");
	}

	@Test
	@DisplayName("Polygon 입력도 ST_Multi 로 MultiPolygon 으로 정규화된다")
	void Polygon입력도_ST_Multi로_MultiPolygon으로_정규화된다() {
		String code = syntheticCode("4");
		// 입력은 단일 Polygon 이지만 저장 타입은 MULTIPOLYGON(스키마 타입) 이어야 한다.
		upsert(code, "합성동", POLYGON);
		em.flush();

		String type = (String) scalar(
			"SELECT ST_GeometryType(boundary_geom::geometry) FROM regions WHERE region_code = :code", code);
		assertThat(type).isEqualTo("ST_MultiPolygon");
	}
}
