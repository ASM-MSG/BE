package com.msg.fillmap.region.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionStatView;
import com.msg.fillmap.region.service.RegionStatsQueryService;
import com.msg.fillmap.region.service.RegionView;
import com.msg.fillmap.user.entity.UserRole;

/**
 * RegionController 역지오코딩 MockMvc (MSG-93). 서비스는 mock — 컨트롤러의 상태코드·body 매핑·검증만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RegionController 역지오코딩")
class RegionControllerTest {

	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private RegionQueryService regionQueryService;

	@MockitoBean
	private RegionStatsQueryService regionStatsQueryService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	private RegionStatView statView() {
		return new RegionStatView(
			"1168051500", "서울특별시 강남구 역삼1동", "11680",
			5, 20, new BigDecimal("25.00"), LocalDateTime.of(2026, 7, 20, 10, 0, 0));
	}

	@Test
	@DisplayName("reverse-geocode 는 200 과 regionCode·regionName 을 반환한다")
	void reverse_geocode는_200과_regionCode_regionName을_반환한다() throws Exception {
		given(regionQueryService.resolveByPoint(37.4979, 127.0276))
			.willReturn(Optional.of(new RegionView("1168051500", "서울특별시 강남구 역삼1동", "11680")));

		mockMvc.perform(get("/api/regions/reverse-geocode")
				.param("lat", "37.4979")
				.param("lng", "127.0276")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.regionCode").value("1168051500"))
			.andExpect(jsonPath("$.data.regionName").value("서울특별시 강남구 역삼1동"))
			.andExpect(jsonPath("$.data.parentCode").value("11680"));
	}

	@Test
	@DisplayName("포함 행정동이 없으면 200 과 null body 를 반환한다 (§D3)")
	void 포함_행정동이_없으면_200과_null_body를_반환한다() throws Exception {
		given(regionQueryService.resolveByPoint(38.0, 130.0)).willReturn(Optional.empty());

		mockMvc.perform(get("/api/regions/reverse-geocode")
				.param("lat", "38.0")
				.param("lng", "130.0")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	@DisplayName("서비스범위 밖 좌표는 400 과 6400 을 반환한다")
	void 서비스범위_밖_좌표는_400과_6400을_반환한다() throws Exception {
		given(regionQueryService.resolveByPoint(10.0, 100.0))
			.willThrow(new ApiException(RegionErrorCode.INVALID_COORDINATE));

		mockMvc.perform(get("/api/regions/reverse-geocode")
				.param("lat", "10.0")
				.param("lng", "100.0")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(6400));
	}

	@Test
	@DisplayName("lat 또는 lng 이 없으면 400 이다 (검증)")
	void lat_또는_lng이_없으면_400이다() throws Exception {
		mockMvc.perform(get("/api/regions/reverse-geocode")
				.param("lat", "37.4979")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(6400));
	}

	@Test
	@DisplayName("인증된 요청은 200 과 수집률 리스트를 반환한다")
	void 인증된_요청은_200과_수집률_리스트를_반환한다() throws Exception {
		given(regionStatsQueryService.findStats(eq(USER_ID), isNull(), eq(true)))
			.willReturn(List.of(statView()));

		mockMvc.perform(get("/api/regions/stats")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", hasSize(1)))
			.andExpect(jsonPath("$.data[0].regionCode").value("1168051500"))
			.andExpect(jsonPath("$.data[0].regionName").value("서울특별시 강남구 역삼1동"))
			.andExpect(jsonPath("$.data[0].parentCode").value("11680"))
			.andExpect(jsonPath("$.data[0].collectedCount").value(5))
			.andExpect(jsonPath("$.data[0].totalCount").value(20));
	}

	@Test
	@DisplayName("collectedOnly 를 생략하면 기본값 true 로 동작한다")
	void collectedOnly를_생략하면_기본값_true로_동작한다() throws Exception {
		given(regionStatsQueryService.findStats(eq(USER_ID), isNull(), eq(true)))
			.willReturn(List.of(statView()));

		mockMvc.perform(get("/api/regions/stats")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", hasSize(1)));
	}

	@Test
	@DisplayName("빈 결과는 200 과 빈 배열로 응답한다")
	void 빈_결과는_200과_빈_배열로_응답한다() throws Exception {
		given(regionStatsQueryService.findStats(eq(USER_ID), isNull(), eq(true)))
			.willReturn(List.of());

		mockMvc.perform(get("/api/regions/stats")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	@DisplayName("실존하지 않는 parentCode 는 6404 를 응답한다")
	void 실존하지_않는_parentCode는_6404를_응답한다() throws Exception {
		given(regionStatsQueryService.findStats(eq(USER_ID), eq("99999"), eq(true)))
			.willThrow(new ApiException(RegionErrorCode.REGION_NOT_FOUND));

		mockMvc.perform(get("/api/regions/stats")
				.param("parentCode", "99999")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.developCode").value(6404));
	}

	@Test
	@DisplayName("by-point 는 200 과 현재 위치 행정동 수집률을 반환한다")
	void by_point는_200과_현재위치_행정동_수집률을_반환한다() throws Exception {
		given(regionStatsQueryService.findStatByPoint(eq(USER_ID), eq(37.4979), eq(127.0276)))
			.willReturn(Optional.of(statView()));

		mockMvc.perform(get("/api/regions/stats/by-point")
				.param("lat", "37.4979")
				.param("lng", "127.0276")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.regionCode").value("1168051500"))
			.andExpect(jsonPath("$.data.collectedCount").value(5))
			.andExpect(jsonPath("$.data.totalCount").value(20));
	}

	@Test
	@DisplayName("by-point 는 행정동이 없으면 200 과 null body 를 반환한다")
	void by_point는_행정동이_없으면_200과_null_body를_반환한다() throws Exception {
		given(regionStatsQueryService.findStatByPoint(eq(USER_ID), eq(38.0), eq(130.0)))
			.willReturn(Optional.empty());

		mockMvc.perform(get("/api/regions/stats/by-point")
				.param("lat", "38.0")
				.param("lng", "130.0")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	@DisplayName("by-point 는 lat 이나 lon 이 없으면 6400 이다")
	void by_point는_lat이나_lon이_없으면_6400이다() throws Exception {
		mockMvc.perform(get("/api/regions/stats/by-point")
				.param("lat", "37.4979")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(6400));
	}

	@Test
	@DisplayName("by-point 는 서비스범위 밖 좌표면 6400 이다")
	void by_point는_서비스범위_밖_좌표면_6400이다() throws Exception {
		given(regionStatsQueryService.findStatByPoint(eq(USER_ID), eq(10.0), eq(100.0)))
			.willThrow(new ApiException(RegionErrorCode.INVALID_COORDINATE));

		mockMvc.perform(get("/api/regions/stats/by-point")
				.param("lat", "10.0")
				.param("lng", "100.0")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(6400));
	}

	@Test
	@DisplayName("by-grid 는 200 과 격자 중심 행정동 수집률을 반환한다")
	void by_grid는_200과_격자_중심_행정동_수집률을_반환한다() throws Exception {
		given(regionStatsQueryService.findStatByGrid(USER_ID, "41642_110458"))
			.willReturn(Optional.of(statView()));

		mockMvc.perform(get("/api/regions/stats/by-grid")
				.param("gridId", "41642_110458")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.regionCode").value("1168051500"))
			.andExpect(jsonPath("$.data.collectedCount").value(5));
	}

	@Test
	@DisplayName("by-grid 는 중심점이 무귀속이면 200 과 null body 를 반환한다")
	void by_grid는_중심점이_무귀속이면_200과_null_body를_반환한다() throws Exception {
		given(regionStatsQueryService.findStatByGrid(USER_ID, "1_1"))
			.willReturn(Optional.empty());

		mockMvc.perform(get("/api/regions/stats/by-grid")
				.param("gridId", "1_1")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	@DisplayName("미인증 요청은 401 이다")
	void 미인증_요청은_401이다() throws Exception {
		mockMvc.perform(get("/api/regions/stats"))
			.andExpect(status().isUnauthorized());
	}
}
