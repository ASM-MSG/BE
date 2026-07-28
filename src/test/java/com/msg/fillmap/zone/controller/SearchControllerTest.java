package com.msg.fillmap.zone.controller;

import static org.hamcrest.Matchers.hasSize;
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

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.zone.dto.SearchResultResponseDto;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * SearchController GET /api/search MockMvc (MSG-234). 서비스는 mock — 상태코드·body·q 검증·인증만 본다.
 * 2단 폴백·trim·limit clamp 등 서비스 로직은 SearchServiceTest(실 DB)에서 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SearchController 장소 검색")
class SearchControllerTest {

	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private ZoneQueryService zoneQueryService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("zone 이름이 매치되면 zone 결과를 반환한다")
	void zone_이름이_매치되면_zone_결과를_반환한다() throws Exception {
		given(zoneQueryService.search(eq("서면"), eq(10))).willReturn(List.of(
			new SearchResultResponseDto("ZONE", "서면", "2623051000", "26230",
				35.1495, 129.0507, 35.1603, 129.06795)));

		mockMvc.perform(get("/api/search").param("q", "서면").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body", hasSize(1)))
			.andExpect(jsonPath("$.body[0].type").value("ZONE"))
			.andExpect(jsonPath("$.body[0].name").value("서면"))
			.andExpect(jsonPath("$.body[0].parentCode").value("26230"));
	}

	@Test
	@DisplayName("매치가 없으면 빈 배열 200 이다")
	void 매치가_없으면_빈_배열_200이다() throws Exception {
		given(zoneQueryService.search(eq("없는장소"), eq(10))).willReturn(List.of());

		mockMvc.perform(get("/api/search").param("q", "없는장소").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body", hasSize(0)));
	}

	@Test
	@DisplayName("q 가 없으면 400 이다 (필수 파라미터 누락)")
	void q가_없으면_400이다() throws Exception {
		mockMvc.perform(get("/api/search").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("미인증 요청은 401 이다")
	void 미인증_요청은_401이다() throws Exception {
		mockMvc.perform(get("/api/search").param("q", "서면"))
			.andExpect(status().isUnauthorized());
	}
}
