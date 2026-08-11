package com.msg.fillmap.grid.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.service.GridCellView;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.grid.service.OccupiedGridView;
import com.msg.fillmap.grid.service.RegionAggregateView;
import com.msg.fillmap.user.entity.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GridController")
class GridControllerTest {

	private static final long USER_ID = 42L;
	private static final String GRID_ID = "41642_110458";
	private static final String REGION_NAME = "부산광역시 부산진구 부전1동";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private GridQueryService gridQueryService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("단일 격자 조회 API 는 200 과 점령여부와 videoCount 를 반환한다")
	void 단일_격자_조회_API는_200과_점령여부와_videoCount를_반환한다() throws Exception {
		given(gridQueryService.getCell(anyLong(), eq(GRID_ID)))
			.willReturn(new GridCellView(GRID_ID, true, 3, "서면", "I-6", REGION_NAME));

		mockMvc.perform(get("/api/grids/{gridId}", GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.gridId").value(GRID_ID))
			.andExpect(jsonPath("$.data.occupied").value(true))
			.andExpect(jsonPath("$.data.videoCount").value(3))
			.andExpect(jsonPath("$.data.zoneName").value("서면"))
			.andExpect(jsonPath("$.data.zoneCell").value("I-6"))
			.andExpect(jsonPath("$.data.regionName").value(REGION_NAME));
	}

	@Test
	@DisplayName("뷰포트 조회 API 는 필수 좌표가 없으면 400 이다")
	void 뷰포트_조회_API는_필수_좌표가_없으면_400이다() throws Exception {
		mockMvc.perform(get("/api/grids")
				.param("swLat", "37.5")
				.param("swLng", "127.0")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4401));
	}

	@Test
	@DisplayName("뷰포트 페이지 조회는 200 과 grids 배열과 nextCursor 를 반환한다")
	void 뷰포트_페이지_조회는_200과_grids배열과_nextCursor를_반환한다() throws Exception {
		given(gridQueryService.getOccupiedInViewport(anyLong(), any(ViewportBounds.class), any(), anyInt()))
			.willReturn(new OccupiedGridPage(
				List.of(new OccupiedGridView(GRID_ID, 41642, 110458, "서면", "I-6", REGION_NAME)),
				"NDE2NDNfMTEwNDYw"));

		mockMvc.perform(viewportRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.grids[0].gridId").value(GRID_ID))
			.andExpect(jsonPath("$.data.grids[0].gridY").value(41642))
			.andExpect(jsonPath("$.data.grids[0].gridX").value(110458))
			.andExpect(jsonPath("$.data.grids[0].zoneName").value("서면"))
			.andExpect(jsonPath("$.data.grids[0].zoneCell").value("I-6"))
			.andExpect(jsonPath("$.data.grids[0].regionName").value(REGION_NAME))
			.andExpect(jsonPath("$.data.nextCursor").value("NDE2NDNfMTEwNDYw"));
	}

	@Test
	@DisplayName("마지막 페이지 응답의 nextCursor 는 null 이다")
	void 마지막페이지_응답의_nextCursor는_null이다() throws Exception {
		given(gridQueryService.getOccupiedInViewport(anyLong(), any(ViewportBounds.class), any(), anyInt()))
			.willReturn(new OccupiedGridPage(
				List.of(new OccupiedGridView(GRID_ID, 41642, 110458, null, null, null)), null));

		mockMvc.perform(viewportRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
	}

	@Test
	@DisplayName("잘못된 커서는 400 과 4403 을 반환한다")
	void 잘못된_커서는_400과_4403을_반환한다() throws Exception {
		given(gridQueryService.getOccupiedInViewport(anyLong(), any(ViewportBounds.class), any(), anyInt()))
			.willThrow(new ApiException(GridErrorCode.INVALID_CURSOR));

		mockMvc.perform(viewportRequest().param("cursor", "!!!not-base64!!!"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4403));
	}

	@Test
	@DisplayName("size 가 상한을 초과하면 400 과 4404 를 반환한다")
	void size가_상한을_초과하면_400과_4404를_반환한다() throws Exception {
		given(gridQueryService.getOccupiedInViewport(anyLong(), any(ViewportBounds.class), any(), anyInt()))
			.willThrow(new ApiException(GridErrorCode.INVALID_PAGE_SIZE));

		mockMvc.perform(viewportRequest().param("size", "5001"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4404));
	}

	@Test
	@DisplayName("집계 조회 API 는 200 과 단위별 항목 배열을 반환한다")
	void 집계_조회_API는_200과_단위별_항목_배열을_반환한다() throws Exception {
		given(gridQueryService.getOccupiedAggregatesInViewport(
			anyLong(), any(ViewportBounds.class), eq(RegionUnit.DONG)))
			.willReturn(List.of(
				new RegionAggregateView("2623058000", "부전2동", 35.162, 129.065, 31),
				new RegionAggregateView(null, null, 35.101, 129.032, 2)));

		mockMvc.perform(aggregationRequest().param("unit", "DONG"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data[0].regionCode").value("2623058000"))
			.andExpect(jsonPath("$.data[0].name").value("부전2동"))
			.andExpect(jsonPath("$.data[0].lat").value(35.162))
			.andExpect(jsonPath("$.data[0].lng").value(129.065))
			.andExpect(jsonPath("$.data[0].count").value(31))
			// 미판정 묶음도 항목으로 온다 — 키와 이름만 null 이고 좌표·개수는 같은 규칙이다 (FR-7)
			.andExpect(jsonPath("$.data[1].regionCode").value(nullValue()))
			.andExpect(jsonPath("$.data[1].name").value(nullValue()))
			.andExpect(jsonPath("$.data[1].count").value(2));
	}

	@Test
	@DisplayName("점령 격자가 없는 뷰포트는 빈 배열로 200 응답한다 (FR-5)")
	void 점령_격자가_없는_뷰포트는_빈_배열로_200_응답한다() throws Exception {
		given(gridQueryService.getOccupiedAggregatesInViewport(anyLong(), any(ViewportBounds.class), any()))
			.willReturn(List.of());

		mockMvc.perform(aggregationRequest().param("unit", "SIDO"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data").isArray())
			.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	@DisplayName("unit 누락이나 미지원 값은 400 과 4405 를 반환한다")
	void unit_누락이나_미지원_값은_400과_4405를_반환한다() throws Exception {
		mockMvc.perform(aggregationRequest())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4405));

		mockMvc.perform(aggregationRequest().param("unit", "EUPMYEON"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4405));
	}

	@Test
	@DisplayName("소문자 unit 값도 허용된다")
	void 소문자_unit_값도_허용된다() throws Exception {
		given(gridQueryService.getOccupiedAggregatesInViewport(
			anyLong(), any(ViewportBounds.class), eq(RegionUnit.SIGUNGU)))
			.willReturn(List.of(new RegionAggregateView("26230", "부산진구", 35.16, 129.06, 43)));

		mockMvc.perform(aggregationRequest().param("unit", "sigungu"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].regionCode").value("26230"));
	}

	@Test
	@DisplayName("집계 조회도 필수 좌표가 없으면 400 과 4401 이다")
	void 집계_조회도_필수_좌표가_없으면_400과_4401이다() throws Exception {
		mockMvc.perform(get("/api/grids/aggregation")
				.param("swLat", "37.5")
				.param("unit", "DONG")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4401));
	}

	@Test
	@DisplayName("단위별 상한을 넘으면 400 과 4402 를 반환한다")
	void 단위별_상한을_넘으면_400과_4402를_반환한다() throws Exception {
		given(gridQueryService.getOccupiedAggregatesInViewport(anyLong(), any(ViewportBounds.class), any()))
			.willThrow(new ApiException(GridErrorCode.VIEWPORT_TOO_LARGE));

		mockMvc.perform(aggregationRequest().param("unit", "DONG"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4402));
	}

	private MockHttpServletRequestBuilder viewportRequest() {
		return get("/api/grids")
			.param("swLat", "37.50")
			.param("swLng", "127.00")
			.param("neLat", "37.55")
			.param("neLng", "127.05")
			.header(HttpHeaders.AUTHORIZATION, bearer());
	}

	private MockHttpServletRequestBuilder aggregationRequest() {
		return get("/api/grids/aggregation")
			.param("swLat", "37.50")
			.param("swLng", "127.00")
			.param("neLat", "37.55")
			.param("neLng", "127.05")
			.header(HttpHeaders.AUTHORIZATION, bearer());
	}
}
