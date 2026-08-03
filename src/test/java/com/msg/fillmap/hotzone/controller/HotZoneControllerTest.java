package com.msg.fillmap.hotzone.controller;

import static org.mockito.ArgumentMatchers.any;
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
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.hotzone.service.HotZoneService;
import com.msg.fillmap.hotzone.service.HotZoneView;
import com.msg.fillmap.user.entity.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("HotZoneController")
class HotZoneControllerTest {

	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private HotZoneService hotZoneService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("뷰포트 파라미터가 누락되면 공통 400 이다")
	void 뷰포트_파라미터가_누락되면_공통_400이다() throws Exception {
		mockMvc.perform(get("/api/hotzones")
				.param("swLat", "37.50")
				.param("swLng", "127.00")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));   // 전역 핸들러 경로 — 8400 아님
	}

	@Test
	@DisplayName("응답에 user_id 가 포함되지 않는다")
	void 응답에_user_id가_포함되지_않는다() throws Exception {
		given(hotZoneService.getHotZones(any(ViewportBounds.class)))
			.willReturn(List.of(new HotZoneView("41642_110458", 41642, 110458, 5L)));

		mockMvc.perform(hotZoneRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hotZones[0].gridId").value("41642_110458"))
			.andExpect(jsonPath("$.data.hotZones[0].userId").doesNotExist())
			.andExpect(jsonPath("$.data.hotZones[0].user_id").doesNotExist());
	}

	@Test
	@DisplayName("핫구역 목록이 핫스코어 내림차순 JSON 으로 직렬화된다")
	void 핫구역_목록이_핫스코어_내림차순_JSON으로_직렬화된다() throws Exception {
		given(hotZoneService.getHotZones(any(ViewportBounds.class)))
			.willReturn(List.of(
				new HotZoneView("41677_110443", 41677, 110443, 7L),
				new HotZoneView("41642_110458", 41642, 110458, 5L)));

		mockMvc.perform(hotZoneRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.hotZones[0].gridId").value("41677_110443"))
			.andExpect(jsonPath("$.data.hotZones[0].gridY").value(41677))
			.andExpect(jsonPath("$.data.hotZones[0].gridX").value(110443))
			.andExpect(jsonPath("$.data.hotZones[0].score").value(7))
			.andExpect(jsonPath("$.data.hotZones[1].gridId").value("41642_110458"))
			.andExpect(jsonPath("$.data.hotZones[1].score").value(5));
	}

	private MockHttpServletRequestBuilder hotZoneRequest() {
		return get("/api/hotzones")
			.param("swLat", "37.50")
			.param("swLng", "127.00")
			.param("neLat", "37.55")
			.param("neLng", "127.05")
			.header(HttpHeaders.AUTHORIZATION, bearer());
	}
}
