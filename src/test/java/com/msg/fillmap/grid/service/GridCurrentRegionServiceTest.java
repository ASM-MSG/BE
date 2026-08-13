package com.msg.fillmap.grid.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.repository.GridRepository;
import com.msg.fillmap.grid.repository.RegionAggregateProjection;
import com.msg.fillmap.grid.repository.RegionGridSummaryProjection;
import com.msg.fillmap.grid.service.impl.GridQueryServiceImpl;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionView;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

@ExtendWith(MockitoExtension.class)
class GridCurrentRegionServiceTest {

	private static final long USER_ID = 42L;

	@Mock
	private GridRepository gridRepository;

	@Mock
	private ZoneNameQueryService zoneNameQueryService;

	@Mock
	private RegionQueryService regionQueryService;

	@Mock
	private RegionGridSummaryProjection summary;

	@Mock
	private RegionAggregateProjection aggregate;

	private GridQueryService gridQueryService;

	@BeforeEach
	void setUp() {
		gridQueryService = new GridQueryServiceImpl(gridRepository, zoneNameQueryService, regionQueryService);
	}

	@Test
	void 집계_응답은_뷰포트_중심의_현재_동네를_담는다() {
		ViewportBounds bounds = new ViewportBounds(35.15, 129.05, 35.17, 129.07);
		given(regionQueryService.resolveByPoint(anyDouble(), anyDouble()))
			.willReturn(Optional.of(new RegionView("2623058000", "부산광역시 부산진구 부전2동", "26230")));
		given(gridRepository.summarizeOccupiedByRegion(USER_ID, "2623058000")).willReturn(summary);
		given(summary.getGridCount()).willReturn(5);
		given(summary.getVideoCount()).willReturn(4_000_000_000L);

		GridAggregationView result =
			gridQueryService.getOccupiedAggregatesWithCurrentRegion(USER_ID, bounds, RegionUnit.DONG);

		assertThat(result.currentRegion())
			.isEqualTo(new CurrentRegionView("2623058000", "부전2동", 5, 4_000_000_000L));
		assertThat(result.items()).isEmpty();
		verify(regionQueryService).resolveByPoint(35.16, 129.06);
	}

	@Test
	void 집계_단위와_1km_초과_여부에_관계없이_현재_동네를_판정한다() {
		ViewportBounds overOneKilometer = new ViewportBounds(35.15, 129.05, 35.17, 129.07);
		given(regionQueryService.resolveByPoint(35.16, 129.06))
			.willReturn(Optional.of(new RegionView("2623058000", "부산광역시 부산진구 부전2동", "26230")));
		given(gridRepository.summarizeOccupiedByRegion(USER_ID, "2623058000")).willReturn(summary);
		given(summary.getGridCount()).willReturn(1);
		given(summary.getVideoCount()).willReturn(2L);

		for (RegionUnit unit : RegionUnit.values()) {
			GridAggregationView result = gridQueryService.getOccupiedAggregatesWithCurrentRegion(
				USER_ID, overOneKilometer, unit);

			assertThat(result.currentRegion().regionCode()).isEqualTo("2623058000");
		}

		verify(regionQueryService, times(3)).resolveByPoint(35.16, 129.06);
	}

	@Test
	void 잘못된_bbox와_단위별_상한_초과는_현재_동네_판정_전에_실패한다() {
		ViewportBounds inverted = new ViewportBounds(35.17, 129.07, 35.15, 129.05);
		ViewportBounds tooWide = new ViewportBounds(35.0, 129.0, 36.01, 129.1);

		assertThatThrownBy(() -> gridQueryService.getOccupiedAggregatesWithCurrentRegion(
			USER_ID, inverted, RegionUnit.DONG))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_VIEWPORT);
		assertThatThrownBy(() -> gridQueryService.getOccupiedAggregatesWithCurrentRegion(
			USER_ID, tooWide, RegionUnit.DONG))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.VIEWPORT_TOO_LARGE);
		verifyNoInteractions(regionQueryService);
	}

	@Test
	void 같은_동_안의_이동과_unit_변경에도_동_전체_summary와_items는_독립적이다() {
		ViewportBounds first = new ViewportBounds(35.15, 129.05, 35.16, 129.06);
		ViewportBounds moved = new ViewportBounds(35.16, 129.06, 35.17, 129.07);
		given(regionQueryService.resolveByPoint(anyDouble(), anyDouble()))
			.willReturn(Optional.of(new RegionView("2623058000", "부산광역시 부산진구 부전2동", "26230")));
		given(gridRepository.summarizeOccupiedByRegion(USER_ID, "2623058000")).willReturn(summary);
		given(summary.getGridCount()).willReturn(0);
		given(summary.getVideoCount()).willReturn(0L);
		given(gridRepository.aggregateOccupiedInRange(
			anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyInt()))
			.willReturn(List.of(aggregate));
		given(aggregate.getRegionCode()).willReturn("2623059000");
		given(aggregate.getName()).willReturn("전포동");
		given(aggregate.getLat()).willReturn(35.17);
		given(aggregate.getLng()).willReturn(129.07);
		given(aggregate.getCount()).willReturn(2L);

		GridAggregationView dong =
			gridQueryService.getOccupiedAggregatesWithCurrentRegion(USER_ID, first, RegionUnit.DONG);
		GridAggregationView sigungu =
			gridQueryService.getOccupiedAggregatesWithCurrentRegion(USER_ID, moved, RegionUnit.SIGUNGU);

		assertThat(dong.currentRegion())
			.isEqualTo(new CurrentRegionView("2623058000", "부전2동", 0, 0L));
		assertThat(sigungu.currentRegion()).isEqualTo(dong.currentRegion());
		assertThat(dong.items()).hasSize(1);
		assertThat(sigungu.items()).hasSize(1);
		verify(gridRepository, times(2)).summarizeOccupiedByRegion(USER_ID, "2623058000");
	}

	@Test
	void 중심이_서비스_범위_밖이면_행정동_판정_쿼리를_아예_호출하지_않는다() {
		ViewportBounds tokyo = new ViewportBounds(35.67, 139.64, 35.69, 139.66);
		GridAggregationView result =
			gridQueryService.getOccupiedAggregatesWithCurrentRegion(USER_ID, tokyo, RegionUnit.DONG);

		assertThat(result.currentRegion()).isNull();
		verifyNoInteractions(regionQueryService);
	}

	@Test
	void 중심이_해상이면_동_전체_카운트_쿼리도_호출하지_않는다() {
		ViewportBounds sea = new ViewportBounds(36.39, 124.39, 36.41, 124.41);
		given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.empty());

		GridAggregationView result =
			gridQueryService.getOccupiedAggregatesWithCurrentRegion(USER_ID, sea, RegionUnit.DONG);

		assertThat(result.currentRegion()).isNull();
		verify(gridRepository, never()).summarizeOccupiedByRegion(anyLong(), anyString());
	}
}
