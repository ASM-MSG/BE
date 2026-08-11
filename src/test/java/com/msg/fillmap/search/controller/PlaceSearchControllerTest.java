package com.msg.fillmap.search.controller;

import static org.hamcrest.Matchers.hasSize;
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

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.search.dto.PlaceSearchResponseDto;
import com.msg.fillmap.search.exception.SearchErrorCode;
import com.msg.fillmap.search.service.PlaceSearchService;
import com.msg.fillmap.user.entity.UserRole;

/**
 * PlaceSearchController MockMvc (MSG-251). 서비스는 mock — 컨트롤러의 상태코드·SuccessResponse 포맷·
 * 에러 매핑만 검증한다. DB fixture 0 (search 는 공유 로컬 DB 무접점 — §D7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("PlaceSearchController 장소 검색")
class PlaceSearchControllerTest {

	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private PlaceSearchService placeSearchService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: FR-SEARCH-01, FR-ZONE-05
	@Test
	@DisplayName("성공은 SuccessResponse 포맷으로 장소 리스트를 반환한다")
	void 성공은_SuccessResponse_포맷으로_장소_리스트를_반환한다() throws Exception {
		given(placeSearchService.searchPlaces(USER_ID, "부산대")).willReturn(List.of(new PlaceSearchResponseDto(
			"부산대학교", "부산 금정구 부산대학로 63번길 2", 35.23272, 129.08246, "39147_112245", "부산대", "B-3")));

		mockMvc.perform(get("/api/search/places")
				.param("q", "부산대")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", hasSize(1)))
			.andExpect(jsonPath("$.data[0].name").value("부산대학교"))
			.andExpect(jsonPath("$.data[0].address").value("부산 금정구 부산대학로 63번길 2"))
			.andExpect(jsonPath("$.data[0].lat").value(35.23272))
			.andExpect(jsonPath("$.data[0].lng").value(129.08246))
			.andExpect(jsonPath("$.data[0].gridId").value("39147_112245"))
			.andExpect(jsonPath("$.data[0].zoneName").value("부산대"))
			.andExpect(jsonPath("$.data[0].zoneCell").value("B-3"));
	}

	// 검증: FR-SEARCH-02
	@Test
	@DisplayName("q 가 공백이면 에러가 아니라 200 과 빈 배열이다 (§D3 trim 가드는 서비스 단위 테스트가 검증)")
	void q가_공백이면_200과_빈_배열이다() throws Exception {
		given(placeSearchService.searchPlaces(USER_ID, "   ")).willReturn(List.of());

		mockMvc.perform(get("/api/search/places")
				.param("q", "   ")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", hasSize(0)));
	}

	// 검증: FR-SEARCH-02
	@Test
	@DisplayName("q 누락이면 400 이다 (global MissingServletRequestParameter — 신규 코드 0)")
	void q_누락이면_400이다() throws Exception {
		mockMvc.perform(get("/api/search/places")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}

	@Test
	@DisplayName("미인증 요청은 401 이다 (SecurityConfig anyRequest — 무변경)")
	void 미인증_요청은_401이다() throws Exception {
		mockMvc.perform(get("/api/search/places").param("q", "부산대"))
			.andExpect(status().isUnauthorized());
	}

	// 검증: FR-SEARCH-04
	@Test
	@DisplayName("업스트림 실패면 502 와 developCode 5502 다 (§D3 단일 수렴)")
	void 업스트림_실패면_502와_developCode_5502다() throws Exception {
		given(placeSearchService.searchPlaces(USER_ID, "부산대"))
			.willThrow(new ApiException(SearchErrorCode.SEARCH_UPSTREAM_ERROR));

		mockMvc.perform(get("/api/search/places")
				.param("q", "부산대")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.developCode").value(5502));
	}
}
