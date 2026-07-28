package com.msg.fillmap.zone.service;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;
import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static com.msg.fillmap.zone.ZoneTestFixtures.MAX_GRID_X;
import static com.msg.fillmap.zone.ZoneTestFixtures.MAX_GRID_Y;
import static com.msg.fillmap.zone.ZoneTestFixtures.MIN_GRID_X;
import static com.msg.fillmap.zone.ZoneTestFixtures.MIN_GRID_Y;
import static com.msg.fillmap.zone.ZoneTestFixtures.zone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.zone.dto.SearchResultResponseDto;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.repository.ZoneRepository;

/**
 * ZoneQueryService.search 통합 (실 PostgreSQL) — zones→regions 2단 폴백·trim 가드·limit clamp 검증.
 * 합성 zone("m234" 접두)·합성 region(999 대역 코드) + @Transactional 롤백으로 공유 DB 를 오염시키지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("ZoneQueryService 검색 — zones→regions 2단 폴백 (실 PostgreSQL)")
class SearchServiceTest {

	@Autowired
	private ZoneQueryService zoneQueryService;

	@Autowired
	private ZoneRepository zoneRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	private void seedZone(Zone z) {
		zoneRepository.saveAndFlush(z);
	}

	/** 합성 행정동 한 건. bbox 검증을 위해 위경도 직사각형 경계로 넣는다(ST_Envelope 결과 = 이 사각형). */
	private void seedRegion(String code, String name, double minLon, double minLat, double maxLon, double maxLat) {
		regionRepository.upsert(code, name, code.substring(0, 5),
			rectanglePolygonJson(minLon, minLat, maxLon, maxLat), CELL_AREA_M2);
		em.flush();
	}

	@Test
	@DisplayName("zone 이름이 매치되면 zone 결과를 반환한다 (type=ZONE)")
	void zone_이름이_매치되면_zone_결과를_반환한다() {
		String regionCode = syntheticCode("5000001");
		seedRegion(regionCode, "m234서면동", 129.0, 35.1, 129.02, 35.12);
		seedZone(Zone.builder().zoneKey("m234-seomyeon").name("m234서면").regionCode(regionCode)
			.minGridY(MIN_GRID_Y).maxGridY(MAX_GRID_Y).minGridX(MIN_GRID_X).maxGridX(MAX_GRID_X).priority(0).build());

		List<SearchResultResponseDto> results = zoneQueryService.search("m234서면", 10);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).type()).isEqualTo("ZONE");
		assertThat(results.get(0).name()).isEqualTo("m234서면");
		assertThat(results.get(0).parentCode()).isEqualTo("99950"); // region_code 앞 5자리
	}

	@Test
	@DisplayName("zone 결과의 bbox 는 사각형 정수 산술과 일치한다 (SW=min·step, NE=(max+1)·step)")
	void zone_결과의_bbox는_사각형_정수_산술과_일치한다() {
		seedZone(zone("bbox", "bbox구역", 45100, 45110, 112200, 112220, 0));

		SearchResultResponseDto r = zoneQueryService.search("m234bbox구역", 10).get(0);

		assertThat(r.minLat()).isCloseTo(45100 * GRID_LAT_STEP, within(1e-9));
		assertThat(r.minLon()).isCloseTo(112200 * GRID_LNG_STEP, within(1e-9));
		assertThat(r.maxLat()).isCloseTo(45111 * GRID_LAT_STEP, within(1e-9));
		assertThat(r.maxLon()).isCloseTo(112221 * GRID_LNG_STEP, within(1e-9));
	}

	@Test
	@DisplayName("zone 매치가 있으면 regions 폴백을 타지 않는다 (2단 폴백 — zone 우선)")
	void zone_매치가_있으면_regions_폴백을_타지_않는다() {
		seedRegion(syntheticCode("5000002"), "m234겹침동", 129.0, 35.1, 129.02, 35.12);
		seedZone(zone("overlap", "겹침", 0));

		List<SearchResultResponseDto> results = zoneQueryService.search("m234겹침", 10);

		assertThat(results).isNotEmpty();
		assertThat(results).allMatch(r -> r.type().equals("ZONE")); // REGION 폴백 결과 없음
	}

	@Test
	@DisplayName("zone 매치가 없으면 regions 이름으로 폴백한다 (type=REGION, ST_Envelope bbox)")
	void zone_매치가_없으면_regions_이름으로_폴백한다() {
		seedRegion(syntheticCode("5000003"), "m234역삼동", 127.05, 37.49, 127.06, 37.50);

		List<SearchResultResponseDto> results = zoneQueryService.search("m234역삼", 10);

		assertThat(results).hasSize(1);
		SearchResultResponseDto r = results.get(0);
		assertThat(r.type()).isEqualTo("REGION");
		assertThat(r.name()).isEqualTo("m234역삼동");
		assertThat(r.parentCode()).isEqualTo("99950");
		assertThat(r.minLat()).isCloseTo(37.49, within(1e-4));
		assertThat(r.maxLat()).isCloseTo(37.50, within(1e-4));
		assertThat(r.minLon()).isCloseTo(127.05, within(1e-4));
		assertThat(r.maxLon()).isCloseTo(127.06, within(1e-4));
	}

	@Test
	@DisplayName("동명 행정동은 parentCode 로 구분된다")
	void 동명_행정동은_parentCode로_구분된다() {
		seedRegion(syntheticCode("5000004"), "m234중앙동", 127.05, 37.49, 127.06, 37.50);
		seedRegion(syntheticCode("5100005"), "m234중앙동", 127.07, 37.51, 127.08, 37.52);

		List<SearchResultResponseDto> results = zoneQueryService.search("m234중앙동", 10);

		assertThat(results).hasSize(2);
		assertThat(results).allMatch(r -> r.type().equals("REGION"));
		assertThat(results).extracting(SearchResultResponseDto::parentCode)
			.containsExactlyInAnyOrder("99950", "99951");
	}

	@Test
	@DisplayName("매치가 없으면 빈 배열 200 이다")
	void 매치가_없으면_빈_배열_200이다() {
		assertThat(zoneQueryService.search("m234존재하지않는장소", 10)).isEmpty();
	}

	@Test
	@DisplayName("q 가 공백이면 zone 과 regions 를 조회하지 않고 빈 배열 200 이다 (trim 가드)")
	void q가_공백이면_zone과_regions를_조회하지_않고_빈_배열_200이다() {
		seedZone(zone("guard", "가드존", 0)); // 존재하는 zone — 가드 없으면 ILIKE '%%' 로 전건 매치될 것

		assertThat(zoneQueryService.search("   ", 10)).isEmpty();
	}

	@Test
	@DisplayName("limit 은 zone 분기에도 적용된다 (zone 매치 수 > limit → 절단)")
	void limit은_zone_분기에도_적용된다() {
		seedZone(zone("limit-1", "리밋", 0));
		seedZone(zone("limit-2", "리밋", 0));
		seedZone(zone("limit-3", "리밋", 0));

		assertThat(zoneQueryService.search("m234리밋", 2)).hasSize(2);
	}

	@Test
	@DisplayName("limit 이 음수여도 최소 1 로 clamp 되어 예외 없이 동작한다")
	void limit이_음수여도_최소_1로_clamp되어_동작한다() {
		seedZone(zone("clamp", "클램프", 0));

		// clamp 없으면 Stream.limit(음수) 가 IllegalArgumentException 을 던진다
		assertThat(zoneQueryService.search("m234클램프", -5)).hasSize(1);
	}

	@Test
	@DisplayName("앞뒤 공백이 있는 q 는 trim 되어 regions 폴백에서도 매치된다")
	void 앞뒤_공백이_있는_q는_trim되어_regions_폴백에서도_매치된다() {
		seedRegion(syntheticCode("5000006"), "m234트림동", 127.07, 37.48, 127.08, 37.49);

		List<SearchResultResponseDto> results = zoneQueryService.search("  m234트림  ", 10);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).type()).isEqualTo("REGION");
		assertThat(results.get(0).name()).isEqualTo("m234트림동");
	}

	@Test
	@DisplayName("q 의 % 는 와일드카드가 아니라 리터럴로 매치된다 (regions 폴백 전건 반환 금지)")
	void q의_퍼센트는_와일드카드가_아니라_리터럴로_매치된다() {
		seedRegion(syntheticCode("5000007"), "m234이스케이프동", 127.05, 37.49, 127.06, 37.50); // % 없는 이름 — 매치되면 안 됨

		assertThat(zoneQueryService.search("%", 10)).isEmpty();
	}

	@Test
	@DisplayName("q 의 _ 는 임의 한 글자가 아니라 리터럴 _ 포함 이름만 매치한다")
	void q의_언더스코어는_리터럴로만_매치된다() {
		seedRegion(syntheticCode("5000008"), "m234언더_바동", 127.05, 37.49, 127.06, 37.50);
		seedRegion(syntheticCode("5000009"), "m234언더바동", 127.07, 37.51, 127.08, 37.52); // _ 없는 이름 — 매치되면 안 됨

		List<SearchResultResponseDto> results = zoneQueryService.search("_", 10);

		assertThat(results).extracting(SearchResultResponseDto::name).contains("m234언더_바동");
		assertThat(results).extracting(SearchResultResponseDto::name).allMatch(n -> n.contains("_"));
	}

	@Test
	@DisplayName("검색 zone branch 에 ST_ geospatial 연산이 없다 (정수 산술 bbox)")
	void 검색_zone_branch에_ST__geospatial_연산이_없다() {
		seedZone(zone("nogeo", "노지오", 45100, 45108, 112200, 112215, 0));

		SearchResultResponseDto r = zoneQueryService.search("m234노지오", 10).get(0);

		// zone bbox 는 GridConstants 정수 산술과 정확히 일치한다 — ST_Envelope(기하 연산) 를 타지 않는다는 관찰
		assertThat(r.minLat()).isEqualTo(45100 * GRID_LAT_STEP);
		assertThat(r.minLon()).isEqualTo(112200 * GRID_LNG_STEP);
		assertThat(r.maxLat()).isEqualTo((45108 + 1) * GRID_LAT_STEP);
		assertThat(r.maxLon()).isEqualTo((112215 + 1) * GRID_LNG_STEP);
	}
}
