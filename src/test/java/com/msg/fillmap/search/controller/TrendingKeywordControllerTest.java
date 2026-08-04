package com.msg.fillmap.search.controller;

import static org.hamcrest.Matchers.aMapWithSize;
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
import com.msg.fillmap.search.dto.TrendingKeywordResponseDto;
import com.msg.fillmap.search.service.TrendingKeywordQueryService;
import com.msg.fillmap.user.entity.UserRole;

/**
 * TrendingKeywordController MockMvc (MSG-258). 서비스는 mock — 컨트롤러의 상태코드·SuccessResponse
 * 포맷·응답 필드(rank·keyword 만)만 검증한다. 집계 쿼리 자체는 조회 서비스 통합 테스트 소관.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("TrendingKeywordController 인기 검색어 순위")
class TrendingKeywordControllerTest {

	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private TrendingKeywordQueryService trendingKeywordQueryService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("성공은 SuccessResponse 포맷으로 순위 리스트를 반환한다")
	void 성공은_SuccessResponse_포맷으로_순위_리스트를_반환한다() throws Exception {
		given(trendingKeywordQueryService.findTop10()).willReturn(List.of(
			new TrendingKeywordResponseDto(1, "홍대 카페"),
			new TrendingKeywordResponseDto(2, "부산대")));

		mockMvc.perform(get("/api/search/trending")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", hasSize(2)))
			.andExpect(jsonPath("$.data[0].rank").value(1))
			.andExpect(jsonPath("$.data[0].keyword").value("홍대 카페"))
			.andExpect(jsonPath("$.data[1].rank").value(2))
			.andExpect(jsonPath("$.data[1].keyword").value("부산대"));
	}

	@Test
	@DisplayName("응답에 검색 횟수와 장소 정보가 포함되지 않는다 (FR-4·8)")
	void 응답에_검색_횟수와_장소_정보가_포함되지_않는다() throws Exception {
		given(trendingKeywordQueryService.findTop10())
			.willReturn(List.of(new TrendingKeywordResponseDto(1, "홍대 카페")));

		mockMvc.perform(get("/api/search/trending")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0]", aMapWithSize(2)))   // rank·keyword 뿐 — count·좌표·주소 없음
			.andExpect(jsonPath("$.data[0].rank").exists())
			.andExpect(jsonPath("$.data[0].keyword").exists());
	}

	@Test
	@DisplayName("집계가 없으면 200 과 빈 배열이다 (FR-5)")
	void 집계가_없으면_200과_빈_배열이다() throws Exception {
		given(trendingKeywordQueryService.findTop10()).willReturn(List.of());

		mockMvc.perform(get("/api/search/trending")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	@DisplayName("미인증 요청은 401 이다 (SecurityConfig anyRequest — 무변경)")
	void 미인증_요청은_401이다() throws Exception {
		mockMvc.perform(get("/api/search/trending"))
			.andExpect(status().isUnauthorized());
	}
}
