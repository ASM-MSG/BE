package com.msg.fillmap.region.seed;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.region.repository.RegionRepository;

/**
 * 공유 로컬 DB 오염 방지 계약(성공 기준 5 · MEMORY 'shared local DB') 검증.
 * 시딩 테스트는 합성 999 대역 코드 + @Transactional 롤백만 쓰고, regions 를 truncate 하지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("시딩 테스트 격리 — 합성 코드·롤백으로 공유 DB 미오염")
class RegionSeedIsolationTest {

	private static final String POLYGON = rectanglePolygonJson(127.0, 37.5, 127.001, 37.501);

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	private long countReal() {
		em.flush();
		Number n = (Number) em.createNativeQuery(
				"SELECT COUNT(*) FROM regions WHERE region_code NOT LIKE '999%'")
			.getSingleResult();
		return n.longValue();
	}

	private long countCode(String code) {
		em.flush();
		Number n = (Number) em.createNativeQuery(
				"SELECT COUNT(*) FROM regions WHERE region_code = :code")
			.setParameter("code", code)
			.getSingleResult();
		return n.longValue();
	}

	@Test
	@DisplayName("시딩 테스트는 합성 region_code 만 사용하고 실데이터 row 를 건드리지 않는다")
	void 시딩테스트는_합성_region_code만_사용하고_실데이터_row를_건드리지_않는다() {
		String code = syntheticCode("300");
		long realBefore = countReal();

		regionRepository.upsert(code, "합성동", code.substring(0, 5), POLYGON, CELL_AREA_M2);

		assertThat(code).startsWith("999");
		assertThat(countReal()).isEqualTo(realBefore);
	}

	@Test
	@DisplayName("@Transactional 롤백으로 공유 DB 에 잔여 seed 가 남지 않는다")
	void Transactional_롤백으로_공유DB에_잔여_seed가_남지않는다() {
		String code = syntheticCode("301");
		regionRepository.upsert(code, "합성동", code.substring(0, 5), POLYGON, CELL_AREA_M2);
		assertThat(countCode(code)).isEqualTo(1L);

		// 트랜잭션을 롤백으로 끝내면 합성 row 가 사라짐을 실제로 확인한다.
		TestTransaction.flagForRollback();
		TestTransaction.end();

		TestTransaction.start();
		assertThat(countCode(code)).isEqualTo(0L);
	}
}
