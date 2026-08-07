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
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.service.GridCellView;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.grid.service.OccupiedGridView;
import com.msg.fillmap.user.entity.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GridController")
class GridControllerTest {

	private static final long USER_ID = 42L;
	private static final String GRID_ID = "41642_110458";

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
			.willReturn(new GridCellView(GRID_ID, true, 3, "서면", "I-6"));

		mockMvc.perform(get("/api/grids/{gridId}", GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.gridId").value(GRID_ID))
			.andExpect(jsonPath("$.data.occupied").value(true))
			.andExpect(jsonPath("$.data.videoCount").value(3))
			.andExpect(jsonPath("$.data.zoneName").value("서면"))
			.andExpect(jsonPath("$.data.zoneCell").value("I-6"));
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
				List.of(new OccupiedGridView(GRID_ID, 41642, 110458, "서면", "I-6")), "NDE2NDNfMTEwNDYw"));

		mockMvc.perform(viewportRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.grids[0].gridId").value(GRID_ID))
			.andExpect(jsonPath("$.data.grids[0].gridY").value(41642))
			.andExpect(jsonPath("$.data.grids[0].gridX").value(110458))
			.andExpect(jsonPath("$.data.grids[0].zoneName").value("서면"))
			.andExpect(jsonPath("$.data.grids[0].zoneCell").value("I-6"))
			.andExpect(jsonPath("$.data.nextCursor").value("NDE2NDNfMTEwNDYw"));
	}

	@Test
	@DisplayName("마지막 페이지 응답의 nextCursor 는 null 이다")
	void 마지막페이지_응답의_nextCursor는_null이다() throws Exception {
		given(gridQueryService.getOccupiedInViewport(anyLong(), any(ViewportBounds.class), any(), anyInt()))
			.willReturn(new OccupiedGridPage(
				List.of(new OccupiedGridView(GRID_ID, 41642, 110458, null, null)), null));

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

	private MockHttpServletRequestBuilder viewportRequest() {
		return get("/api/grids")
			.param("swLat", "37.50")
			.param("swLng", "127.00")
			.param("neLat", "37.55")
			.param("neLng", "127.05")
			.header(HttpHeaders.AUTHORIZATION, bearer());
	}
}
