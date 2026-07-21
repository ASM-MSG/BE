package com.msg.fillmap.region.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("reverse-geocode 는 200 과 regionCode·regionName 을 반환한다")
	void reverse_geocode는_200과_regionCode_regionName을_반환한다() throws Exception {
		given(regionQueryService.resolveByPoint(37.4979, 127.0276))
			.willReturn(Optional.of(new RegionView("1168051500", "서울특별시 강남구 역삼1동", "11680")));

		mockMvc.perform(get("/api/regions/reverse-geocode")
				.param("lat", "37.4979")
				.param("lon", "127.0276")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body.regionCode").value("1168051500"))
			.andExpect(jsonPath("$.body.regionName").value("서울특별시 강남구 역삼1동"))
			.andExpect(jsonPath("$.body.parentCode").value("11680"));
	}

	@Test
	@DisplayName("포함 행정동이 없으면 200 과 null body 를 반환한다 (§D3)")
	void 포함_행정동이_없으면_200과_null_body를_반환한다() throws Exception {
		given(regionQueryService.resolveByPoint(38.0, 130.0)).willReturn(Optional.empty());

		mockMvc.perform(get("/api/regions/reverse-geocode")
				.param("lat", "38.0")
				.param("lon", "130.0")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body").value(nullValue()));
	}

	@Test
	@DisplayName("서비스범위 밖 좌표는 400 과 6400 을 반환한다")
	void 서비스범위_밖_좌표는_400과_6400을_반환한다() throws Exception {
		given(regionQueryService.resolveByPoint(10.0, 100.0))
			.willThrow(new ApiException(RegionErrorCode.INVALID_COORDINATE));

		mockMvc.perform(get("/api/regions/reverse-geocode")
				.param("lat", "10.0")
				.param("lon", "100.0")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(6400));
	}

	@Test
	@DisplayName("lat 또는 lon 이 없으면 400 이다 (검증)")
	void lat_또는_lon이_없으면_400이다() throws Exception {
		mockMvc.perform(get("/api/regions/reverse-geocode")
				.param("lat", "37.4979")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(6400));
	}
}
