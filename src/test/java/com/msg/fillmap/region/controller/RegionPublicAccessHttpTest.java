package com.msg.fillmap.region.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.region.service.RegionDistrictView;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionStatsQueryService;
import com.msg.fillmap.region.service.RegionView;

/**
 * 행정동 조회 2종의 인증 계약 (MSG-467). 지도 홈 상단 칩은 비로그인으로 보이고 로그인은 쓰기부터라는
 * MSG-439·454 원칙의 잔여분 — 칩 좌측 패널의 위치줄과 지역 필터가 이 둘을 부른다. 서비스는 목이라
 * 이 테스트의 변수는 SecurityConfig 등록뿐이다. 열려야 할 둘이 열렸는지와, 사용자별 값인 수집률
 * 4종·비GET·무효 토큰이 함께 풀리지 않았는지를 같이 본다(광역 matcher 회귀 방지).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("행정동 조회 공개 접근 (SecurityConfig)")
class RegionPublicAccessHttpTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RegionQueryService regionQueryService;

	@MockitoBean
	private RegionStatsQueryService regionStatsQueryService;

	// 검증: FR-REGION-02, FR-REGION-15
	@Test
	@DisplayName("무인증으로 행정동 조회 2종이 200으로 성공한다")
	void 무인증으로_행정동_조회_2종이_200으로_성공한다() throws Exception {
		given(regionQueryService.resolveByPoint(anyDouble(), anyDouble()))
			.willReturn(Optional.of(new RegionView("1168051500", "서울특별시 강남구 역삼1동", "11680")));
		given(regionStatsQueryService.findDistricts())
			.willReturn(List.of(new RegionDistrictView("11680", "강남구", 4102L)));

		// 좌표가 빠지면 인가와 무관하게 400 이라 유효 좌표를 싣는다 (§성공 기준 1). 파라미터명은 lng 다.
		mockMvc.perform(get("/api/regions/reverse-geocode").param("lat", "37.4979").param("lng", "127.0276"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.regionCode").value("1168051500"));
		mockMvc.perform(get("/api/regions/districts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", hasSize(1)));
	}

	// 검증: FR-REGION-06, FR-REGION-07, FR-REGION-14
	@Test
	@DisplayName("무인증 수집률 조회 4종은 401로 거절된다 (사용자별 값은 열지 않는다)")
	void 무인증_수집률_조회_4종은_401로_거절된다() throws Exception {
		// 매처를 /api/regions/** 로 넓히면 여기서 깨진다 — 인가 누수이자 principal.userId() NPE 500 가드.
		mockMvc.perform(get("/api/regions/stats")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/regions/stats/by-point").param("lat", "37.4979").param("lng", "127.0276"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/regions/stats/national")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/regions/stats/by-grid").param("gridId", "19422_9582"))
			.andExpect(status().isUnauthorized());
	}

	// 검증: FR-REGION-02, FR-REGION-15
	@Test
	@DisplayName("무인증 비GET 행정동 경로는 401로 거절된다 (GET 한정 계약)")
	void 무인증_비GET_행정동_경로는_401로_거절된다() throws Exception {
		mockMvc.perform(post("/api/regions/districts")).andExpect(status().isUnauthorized());
	}

	// 검증: FR-REGION-02, FR-REGION-15
	@Test
	@DisplayName("공개 GET에서도 무효 토큰은 2401로 거절된다 (토큰 검증 성질 보존)")
	void 공개_GET에서도_무효_토큰은_2401로_거절된다() throws Exception {
		mockMvc.perform(get("/api/regions/reverse-geocode")
				.param("lat", "37.4979").param("lng", "127.0276")
				.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2401));
	}

	// 검증: FR-REGION-02
	@Test
	@DisplayName("무인증 역지오코딩도 좌표가 빠지면 6400이다 (파라미터 검증은 그대로)")
	void 무인증_역지오코딩도_좌표가_빠지면_6400이다() throws Exception {
		mockMvc.perform(get("/api/regions/reverse-geocode").param("lat", "37.4979"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(6400));
	}
}
