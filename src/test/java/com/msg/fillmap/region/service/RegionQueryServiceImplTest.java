package com.msg.fillmap.region.service;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.repository.RegionRepository;

/**
 * RegionQueryServiceImpl.resolveByPoint 통합 (실 PostGIS · 합성 폴리곤 · 롤백 격리).
 * 좌표 검증 → native ST_Covers 조회 → RegionView 매핑, no-match=Optional.empty(§D3) 경로를 검증한다.
 */
@SpringBootTest
@Transactional
@DisplayName("RegionQueryServiceImpl.resolveByPoint (합성 폴리곤 · 롤백 격리)")
class RegionQueryServiceImplTest {

	// 서해 공해상(lon 125, lat 36) 소형 폴리곤 — 육상 실데이터와 겹치지 않는 좌표 대역.
	private static final String CODE = syntheticCode("1");
	private static final String POLYGON = rectanglePolygonJson(124.99, 35.99, 125.01, 36.01);

	@Autowired
	private RegionQueryService regionQueryService;

	@Autowired
	private RegionRepository regionRepository;

	private void seed() {
		regionRepository.upsert(CODE, "합성동", CODE.substring(0, 5), POLYGON, CELL_AREA_M2);
	}

	@Test
	@DisplayName("유효좌표는 포함 행정동 뷰를 반환한다")
	void 유효좌표는_포함_행정동뷰를_반환한다() {
		seed();

		Optional<RegionView> view = regionQueryService.resolveByPoint(36.0, 125.0);

		assertThat(view).isPresent();
		assertThat(view.get().regionCode()).isEqualTo(CODE);
		assertThat(view.get().regionName()).isEqualTo("합성동");
	}

	@Test
	@DisplayName("포함 행정동이 없으면 Optional.empty 를 반환한다 (예외 아님 — §D3)")
	void 포함_행정동이_없으면_Optional_empty를_반환한다() {
		seed();

		// 서비스범위 내이나 어떤 폴리곤에도 속하지 않는 공해상 좌표.
		Optional<RegionView> view = regionQueryService.resolveByPoint(35.5, 125.0);

		assertThat(view).isEmpty();
	}

	@Test
	@DisplayName("서비스범위 밖 좌표는 INVALID_COORDINATE 를 던진다 (6400)")
	void 서비스범위_밖_좌표는_INVALID_COORDINATE를_던진다() {
		assertThatThrownBy(() -> regionQueryService.resolveByPoint(10.0, 100.0))
			.isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).getErrorCode())
			.isEqualTo(RegionErrorCode.INVALID_COORDINATE);
	}

	@Test
	@DisplayName("위경도 순서를 lon, lat 로 바인딩한다 (좌표 뒤집힘 회귀 방지)")
	void 위경도_순서를_lon_lat로_바인딩한다() {
		seed();

		// 폴리곤은 lon=125, lat=36 에 있다. (lat=36, lon=125) 로 호출해 매칭되면 lat→Y·lon→X 바인딩이 옳다.
		// 만약 native 쿼리가 lat/lon 을 뒤집으면 점이 (lon=36, lat=125) 로 가 매칭되지 않는다.
		Optional<RegionView> view = regionQueryService.resolveByPoint(36.0, 125.0);

		assertThat(view).isPresent();
		assertThat(view.get().regionCode()).isEqualTo(CODE);
	}
}
