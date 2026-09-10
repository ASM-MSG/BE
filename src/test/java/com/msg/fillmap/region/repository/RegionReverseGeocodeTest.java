package com.msg.fillmap.region.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * RegionRepository 역지오코딩 native 조회 (실 PostGIS · GEOGRAPHY ST_Covers). 공유 로컬 DB 오염 방지를 위해
 * 실존하지 않는 999 대역 region_code 와 서해 공해상(육상 행정동과 겹치지 않는) 소형 합성 폴리곤만 쓰고 @Transactional 롤백.
 */
// 검증: FR-REGION-02
@SpringBootTest
@Transactional
@DisplayName("RegionRepository 역지오코딩 (ST_Covers · 합성 폴리곤 · 롤백 격리)")
class RegionReverseGeocodeTest {

	// 서해 공해상(lon 125, lat 36) 소형 폴리곤 A. 육상 실데이터와 겹치지 않는 좌표 대역.
	private static final String CODE_A = syntheticCode("1");
	private static final String POLYGON_A = rectanglePolygonJson(124.99, 35.99, 125.01, 36.01);

	// A 의 동편에 변을 공유하는 인접 폴리곤 B (경계선 다중매칭 → LIMIT 1 단일화 검증용).
	private static final String CODE_B = syntheticCode("2");
	private static final String POLYGON_B = rectanglePolygonJson(125.01, 35.99, 125.03, 36.01);

	@Autowired
	private RegionRepository regionRepository;

	private void seed(String code, String polygonJson) {
		regionRepository.upsert(code, "합성동", code.substring(0, 5), polygonJson, CELL_AREA_M2);
	}

	@Test
	@DisplayName("폴리곤 내부 좌표는 그 행정동을 반환한다")
	void 폴리곤_내부_좌표는_그_행정동을_반환한다() {
		seed(CODE_A, POLYGON_A);

		Optional<RegionProjection> found = regionRepository.findContainingRegion(36.0, 125.0);

		assertThat(found).isPresent();
		assertThat(found.get().getRegionCode()).isEqualTo(CODE_A);
	}

	@Test
	@DisplayName("폴리곤 외부 좌표는 결과가 없다 (no-match)")
	void 폴리곤_외부_좌표는_결과가_없다() {
		seed(CODE_A, POLYGON_A);

		Optional<RegionProjection> found = regionRepository.findContainingRegion(35.5, 125.0);

		assertThat(found).isEmpty();
	}

	@Test
	@DisplayName("GEOGRAPHY ST_Covers 는 경계선 좌표도 포함해 단일건을 반환한다 (LIMIT 1)")
	void GEOGRAPHY_ST_Covers는_경계선_좌표도_포함해_단일건을_반환한다() {
		seed(CODE_A, POLYGON_A);
		seed(CODE_B, POLYGON_B);

		// A|B 가 변을 공유하는 경계선(lon 125.01) 위 좌표 — 둘 다 covers 하지만 LIMIT 1 로 단일건.
		Optional<RegionProjection> found = regionRepository.findContainingRegion(36.0, 125.01);

		assertThat(found).isPresent();
	}

	// --- MSG-492: 최근접 행정동 (테스트 23~25) ---

	@Test
	@DisplayName("폴리곤 밖 좌표는 경계까지 가장 가까운 행정동을 반환한다")
	void 폴리곤_밖_좌표는_최근접_행정동을_반환한다() {
		seed(CODE_A, POLYGON_A);

		// A 북쪽 약 1km 바깥. 서해 공해상이라 실데이터 육상 행정동보다 A 가 압도적으로 가깝다.
		Optional<RegionProjection> found = regionRepository.findNearestRegion(36.02, 125.0);

		assertThat(found).isPresent();
		assertThat(found.get().getRegionCode()).isEqualTo(CODE_A);
	}

	@Test
	@DisplayName("폴리곤 안 좌표는 그 행정동을 반환한다 — ST_Covers 와 같은 답")
	void 폴리곤_안_좌표는_ST_Covers와_같은_답을_준다() {
		seed(CODE_A, POLYGON_A);

		Optional<RegionProjection> nearest = regionRepository.findNearestRegion(36.0, 125.0);

		assertThat(nearest).isPresent();
		assertThat(nearest.get().getRegionCode())
			.isEqualTo(regionRepository.findContainingRegion(36.0, 125.0).orElseThrow().getRegionCode());
	}

	@Test
	@DisplayName("경계 동률에서는 region_code 가 작은 쪽으로 결정적으로 갈린다")
	void 경계_동률은_region_code로_결정적으로_갈린다() {
		seed(CODE_A, POLYGON_A);
		seed(CODE_B, POLYGON_B);

		// A|B 공유 변(lon 125.01) 위 — 두 폴리곤 모두 거리 0 이라 KNN 순서만으로는 비결정적이다.
		Optional<RegionProjection> first = regionRepository.findNearestRegion(36.0, 125.01);
		Optional<RegionProjection> second = regionRepository.findNearestRegion(36.0, 125.01);

		assertThat(first).isPresent();
		assertThat(first.get().getRegionCode()).isEqualTo(CODE_A);
		assertThat(second.get().getRegionCode()).isEqualTo(first.get().getRegionCode());
	}

	// --- MSG-493: 거리 상한 있는 최근접 ---

	@Test
	@DisplayName("상한 안이면 최근접 행정동을 반환한다")
	void 상한_안이면_최근접_행정동을_반환한다() {
		seed(CODE_A, POLYGON_A);

		// A 북쪽 약 1km 바깥 — 3km 상한 안.
		Optional<RegionProjection> found = regionRepository.findNearestRegionWithin(36.02, 125.0, 3_000);

		assertThat(found).isPresent();
		assertThat(found.get().getRegionCode()).isEqualTo(CODE_A);
	}

	@Test
	@DisplayName("최근접이 존재해도 상한 밖이면 빈 결과다")
	void 최근접이_존재해도_상한_밖이면_빈_결과다() {
		seed(CODE_A, POLYGON_A);

		// A 북쪽 약 10km — 최근접은 A 지만 3km 상한을 넘는다.
		Optional<RegionProjection> found = regionRepository.findNearestRegionWithin(36.1, 125.0, 3_000);

		assertThat(found).isEmpty();
	}
}
