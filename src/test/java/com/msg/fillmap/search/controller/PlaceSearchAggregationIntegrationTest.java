package com.msg.fillmap.search.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
 * → 검색자 키 전달, 집계 빈 주입)을 HTTP 경로로 검증한다. 집계 서비스가 mock 이라 Redis·DB 무접점이고
 * 비동기 대기도 없다(결정적).
 *
 * MSG-469 로 익명 검색이 열리면서 검색자 키가 사용자 id 하나에서 "로그인 사용자 또는 방문자 세션"으로
 * 넓어졌다 — 익명 분기는 컨트롤러의 헤더 해석과 서비스의 접수 생략이 맞물린 배선이라 이 클래스가 제자리다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("장소 검색 → 검색어 집계 훅 배선")
class PlaceSearchAggregationIntegrationTest {

	private static final long USER_ID = 42L;
	private static final String USER_SEARCHER_KEY = String.valueOf(USER_ID);
	private static final String VIEWER_SESSION_HEADER = "X-Viewer-Session";

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

		verify(searchKeywordCommandService).recordSearch(USER_SEARCHER_KEY, "부산대");
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

		verify(searchKeywordCommandService).recordSearch(USER_SEARCHER_KEY, "부산대");
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

	// 검증: FR-SEARCH-05, FR-SEARCH-06
	@Test
	@DisplayName("익명 검색은 방문자 식별값으로 집계에 접수된다")
	void 익명_검색은_방문자_식별값으로_집계에_접수된다() throws Exception {
		given(kakaoLocalClient.search("부산대")).willReturn(List.of(
			new KakaoPlace("부산대학교", "부산 금정구 장전동 40", "부산 금정구 부산대학로63번길 2", 35.23272, 129.08246)));

		mockMvc.perform(get("/api/search/places")
				.param("q", "부산대")
				.header(VIEWER_SESSION_HEADER, "sess-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));

		verify(searchKeywordCommandService).recordSearch("s:sess-1", "부산대");
	}

	// 검증: FR-SEARCH-01, FR-SEARCH-05
	@Test
	@DisplayName("식별값 없는 익명 검색도 200 이고 집계에만 안 잡힌다")
	void 식별값_없는_익명_검색도_200이고_집계에만_안_잡힌다() throws Exception {
		given(kakaoLocalClient.search("부산대")).willReturn(List.of(
			new KakaoPlace("부산대학교", "부산 금정구 장전동 40", "부산 금정구 부산대학로63번길 2", 35.23272, 129.08246)));

		// 헤더를 붙이기 전의 화면도 검색은 막히지 않아야 한다 — 400 이 아니라 200 이 확정이다 (PRD 8절)
		mockMvc.perform(get("/api/search/places").param("q", "부산대"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("부산대학교"));

		verifyNoInteractions(searchKeywordCommandService);
	}

	// 검증: FR-SEARCH-06
	@Test
	@DisplayName("빈 값과 상한 초과 식별값은 집계에서만 빠진다")
	void 빈_값과_상한_초과_식별값은_집계에서만_빠진다() throws Exception {
		mockMvc.perform(get("/api/search/places").param("q", "부산대").header(VIEWER_SESSION_HEADER, "   "))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/search/places").param("q", "부산대")
				.header(VIEWER_SESSION_HEADER, "a".repeat(65)))
			.andExpect(status().isOk());

		verifyNoInteractions(searchKeywordCommandService);
	}

	// 검증: FR-SEARCH-06
	@Test
	@DisplayName("콜론 포함 식별값은 집계에서만 빠져 다른 조합과 섞이지 않는다")
	void 콜론_포함_식별값은_집계에서만_빠져_다른_조합과_섞이지_않는다() throws Exception {
		// 콜론을 허용했다면 아래 두 요청의 member 가 s:a:b:c 로 같아져 뒤의 검색이 중복으로 지워졌을 자리다 (D4)
		mockMvc.perform(get("/api/search/places").param("q", "c").header(VIEWER_SESSION_HEADER, "a:b"))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/search/places").param("q", "b:c").header(VIEWER_SESSION_HEADER, "a"))
			.andExpect(status().isOk());

		verify(searchKeywordCommandService).recordSearch("s:a", "b:c");
		verifyNoMoreInteractions(searchKeywordCommandService);
	}

	// 검증: FR-SEARCH-05, FR-SEARCH-06
	@Test
	@DisplayName("로그인 요청은 헤더가 와도 사용자 기준으로 접수된다")
	void 로그인_요청은_헤더가_와도_사용자_기준으로_접수된다() throws Exception {
		mockMvc.perform(get("/api/search/places")
				.param("q", "부산대")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.header(VIEWER_SESSION_HEADER, "sess-1"))
			.andExpect(status().isOk());

		verify(searchKeywordCommandService).recordSearch(USER_SEARCHER_KEY, "부산대");
		verifyNoMoreInteractions(searchKeywordCommandService);
	}
}
