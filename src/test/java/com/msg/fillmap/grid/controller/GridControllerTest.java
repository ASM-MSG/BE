package com.msg.fillmap.grid.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.grid.service.GridCellView;
import com.msg.fillmap.grid.service.GridQueryService;
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
			.willReturn(new GridCellView(GRID_ID, true, 3));

		mockMvc.perform(get("/api/grids/{gridId}", GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body.gridId").value(GRID_ID))
			.andExpect(jsonPath("$.body.occupied").value(true))
			.andExpect(jsonPath("$.body.videoCount").value(3));
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
}
