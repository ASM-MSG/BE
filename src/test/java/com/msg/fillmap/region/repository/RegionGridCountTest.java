package com.msg.fillmap.region.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.squareKm2Json;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.region.repository.RegionRepository;

@SpringBootTest
@Transactional
@DisplayName("total_grid_count 산출 (면적 근사, D1) — ROUND(ST_Area/cellAreaM2)")
class RegionGridCountTest {

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	private int gridCountOf(String code, double areaKm2) {
		regionRepository.upsert(code, "합성동", code.substring(0, 5), squareKm2Json(areaKm2), CELL_AREA_M2);
		em.flush();
		Number count = (Number) em.createNativeQuery(
				"SELECT total_grid_count FROM regions WHERE region_code = :code")
			.setParameter("code", code)
			.getSingleResult();
		return count.intValue();
	}

	@Test
	@DisplayName("면적 1제곱킬로미터 경계는 약 100개 격자로 산출된다 (1,000,000 / 10,000)")
	void 면적_1제곱킬로미터_경계는_약_100개_격자로_산출된다() {
		assertThat(gridCountOf(syntheticCode("11"), 1.0)).isBetween(95, 105);
	}

	@Test
	@DisplayName("여의도동 규모(약 8.4km²) 경계는 수백개 격자로 sanity check 된다")
	void 여의도동_규모_경계는_수백개_격자로_sanity_check된다() {
		assertThat(gridCountOf(syntheticCode("12"), 8.4)).isBetween(700, 950);
	}

	@Test
	@DisplayName("boundary 가 클수록 total_grid_count 가 단조증가한다")
	void boundary가_클수록_total_grid_count가_단조증가한다() {
		int small = gridCountOf(syntheticCode("13"), 0.25);
		int medium = gridCountOf(syntheticCode("14"), 1.0);
		int large = gridCountOf(syntheticCode("15"), 4.0);

		assertThat(small).isLessThan(medium);
		assertThat(medium).isLessThan(large);
	}
}
