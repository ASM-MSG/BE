package com.msg.fillmap.zone.repository;

import static com.msg.fillmap.zone.ZoneTestFixtures.MAX_GRID_X;
import static com.msg.fillmap.zone.ZoneTestFixtures.MAX_GRID_Y;
import static com.msg.fillmap.zone.ZoneTestFixtures.MIN_GRID_X;
import static com.msg.fillmap.zone.ZoneTestFixtures.MIN_GRID_Y;
import static com.msg.fillmap.zone.ZoneTestFixtures.zone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.zone.entity.Zone;

/**
 * ZoneRepository·V8 스키마 통합 (실 PostgreSQL) — 합성 zone_key·롤백 격리.
 * 정수 사각형 CHECK 3종·zone_key UNIQUE·geospatial 0(geometry 컬럼/GIST 부재)를 검증한다.
 */
@SpringBootTest
@Transactional
@DisplayName("ZoneRepository·V8 스키마 (실 PostgreSQL) — 합성 zone_key·롤백 격리")
class ZoneRepositoryTest {

	@Autowired
	private ZoneRepository zoneRepository;

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("min 이 max 보다 크면 삽입이 거부된다 (chk_zones_y_range)")
	void min이_max보다_크면_삽입이_거부된다() {
		Zone bad = zone("badrange", "역범위위반", MAX_GRID_Y, MIN_GRID_Y, MIN_GRID_X, MAX_GRID_X, 0);

		assertThatThrownBy(() -> zoneRepository.saveAndFlush(bad))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("행 폭이 25 를 넘으면 삽입이 거부된다 (chk_zones_row_cap — 26행 한계)")
	void 행_폭이_25를_넘으면_삽입이_거부된다() {
		Zone bad = zone("rowcap", "행폭초과", MIN_GRID_Y, MIN_GRID_Y + 26, MIN_GRID_X, MAX_GRID_X, 0);

		assertThatThrownBy(() -> zoneRepository.saveAndFlush(bad))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("region_code 가 null 인 zone 도 삽입된다 (nullable FK)")
	void region_code가_null인_zone도_삽입된다() {
		Zone saved = zoneRepository.saveAndFlush(zone("nullregion", "무귀속구역", 0));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getRegionCode()).isNull();
	}

	@Test
	@DisplayName("같은 zone_key 는 UNIQUE 로 중복 삽입이 거부된다 (자연키)")
	void 같은_zone_key는_중복_삽입이_거부된다() {
		zoneRepository.saveAndFlush(zone("dup", "일번", 0));

		assertThatThrownBy(() -> zoneRepository.saveAndFlush(zone("dup", "이번", 0)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("zones 에 geometry 컬럼과 GIST 인덱스가 없다 (정수 사각형 — 스키마 메타)")
	void zones에_geometry_컬럼과_GIST_인덱스가_없다() {
		Number geomColumns = (Number) em.createNativeQuery("""
			SELECT COUNT(*) FROM information_schema.columns
			WHERE table_name = 'zones' AND udt_name IN ('geometry', 'geography')
			""").getSingleResult();
		Number gistIndexes = (Number) em.createNativeQuery("""
			SELECT COUNT(*) FROM pg_indexes
			WHERE tablename = 'zones' AND indexdef ILIKE '%gist%'
			""").getSingleResult();
		Number checks = (Number) em.createNativeQuery("""
			SELECT COUNT(*) FROM pg_constraint
			WHERE conrelid = 'zones'::regclass AND contype = 'c'
			  AND conname IN ('chk_zones_y_range', 'chk_zones_x_range', 'chk_zones_row_cap')
			""").getSingleResult();

		assertThat(geomColumns.longValue()).isZero();
		assertThat(gistIndexes.longValue()).isZero();
		assertThat(checks.longValue()).isEqualTo(3L); // x-range 포함 CHECK 3종 존재
	}

	@Test
	@DisplayName("구역 목록 조회에 geospatial 연산이 없다 (findAll 전 컬럼 정수 로드)")
	void 구역_목록_조회에_geospatial_연산이_없다() {
		zoneRepository.saveAndFlush(zone("list1", "리스트일", 45100, 45105, 112200, 112210, 3));
		em.clear(); // 영속성 컨텍스트 비워 DB 에서 순수 컬럼 재로드

		Zone loaded = zoneRepository.findByNameContainingIgnoreCaseOrderByPriorityDescZoneKeyAsc("m234리스트일")
			.get(0);

		// geometry 없이 정수 컬럼 전부 그대로 로드된다
		assertThat(loaded.getMinGridY()).isEqualTo(45100);
		assertThat(loaded.getMaxGridY()).isEqualTo(45105);
		assertThat(loaded.getMinGridX()).isEqualTo(112200);
		assertThat(loaded.getMaxGridX()).isEqualTo(112210);
		assertThat(loaded.getPriority()).isEqualTo(3);
	}

	@Test
	@DisplayName("이름 부분일치 검색은 priority DESC, zone_key ASC 로 정렬된다 (§D5)")
	void 이름_부분일치_검색은_priority_DESC_zone_key_ASC로_정렬된다() {
		zoneRepository.saveAndFlush(zone("sort-c", "정렬대상", 5));  // priority 5
		zoneRepository.saveAndFlush(zone("sort-a", "정렬대상", 10)); // priority 10 (최상)
		zoneRepository.saveAndFlush(zone("sort-b", "정렬대상", 10)); // priority 10, zone_key ASC 로 a 뒤

		var hits = zoneRepository.findByNameContainingIgnoreCaseOrderByPriorityDescZoneKeyAsc("m234정렬대상");

		assertThat(hits).extracting(Zone::getZoneKey)
			.containsExactly("m234-sort-a", "m234-sort-b", "m234-sort-c");
	}
}
