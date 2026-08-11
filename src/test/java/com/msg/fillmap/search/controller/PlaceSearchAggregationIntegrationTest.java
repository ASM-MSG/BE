package com.msg.fillmap.search.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.msg.fillmap.search.exception.SearchErrorCode;
import com.msg.fillmap.search.service.KakaoLocalClient;
import com.msg.fillmap.search.service.KakaoLocalClient.KakaoPlace;
import com.msg.fillmap.search.service.SearchKeywordCommandService;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 검색 훅 배선 통합 테스트 (MSG-258 §D1) — 실제 PlaceSearchController·PlaceSearchServiceImpl 빈에
 * 카카오 클라이언트와 집계 서비스만 mock 으로 갈아끼운다. 단위 테스트가 못 보는 배선(@AuthenticationPrincipal
 * → userId 전달, 집계 빈 주입)을 HTTP 경로로 검증한다. 집계 서비스가 mock 이라 Redis·DB 무접점이고
 * 비동기 대기도 없다(결정적).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("장소 검색 → 검색어 집계 훅 배선")
class PlaceSearchAggregationIntegrationTest {

	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private KakaoLocalClient kakaoLocalClient;

	@MockitoBean
	private SearchKeywordCommandService searchKeywordCommandService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: FR-SEARCH-05
	@Test
	@DisplayName("인증된 검색 요청은 principal 의 userId 로 집계에 접수된다")
	void 인증된_검색_요청은_principal의_userId로_집계에_접수된다() throws Exception {
		given(kakaoLocalClient.search("부산대")).willReturn(List.of(
			new KakaoPlace("부산대학교", "부산 금정구 장전동 40", "부산 금정구 부산대학로63번길 2", 35.23272, 129.08246)));

		mockMvc.perform(get("/api/search/places")
				.param("q", "  부산대  ")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk());

		verify(searchKeywordCommandService).recordSearch(USER_ID, "부산대");
	}

	// 검증: FR-SEARCH-05
	@Test
	@DisplayName("카카오 실패(5502) 응답에도 검색어는 집계에 접수된다 (FR-1)")
	void 카카오_실패_응답에도_검색어는_집계에_접수된다() throws Exception {
		given(kakaoLocalClient.search("부산대")).willThrow(new ApiException(SearchErrorCode.SEARCH_UPSTREAM_ERROR));

		mockMvc.perform(get("/api/search/places")
				.param("q", "부산대")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.developCode").value(5502));

		verify(searchKeywordCommandService).recordSearch(USER_ID, "부산대");
	}

	// 검증: FR-SEARCH-02
	@Test
	@DisplayName("트림 후 빈 검색어는 집계도 카카오 호출도 되지 않는다 (FR-9)")
	void 트림_후_빈_검색어는_집계도_카카오_호출도_되지_않는다() throws Exception {
		mockMvc.perform(get("/api/search/places")
				.param("q", "   ")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());

		verifyNoInteractions(searchKeywordCommandService);
		verifyNoInteractions(kakaoLocalClient);
	}
}
