package com.msg.fillmap.zone.controller;

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
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.zone.dto.ZoneResponseDto;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * ZoneController GET /api/zones MockMvc (MSG-234). 서비스는 mock — 상태코드·body 매핑·인증만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ZoneController 구역 목록")
class ZoneControllerTest {

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
	@DisplayName("구역 목록을 전체 반환한다")
	void 구역_목록을_전체_반환한다() throws Exception {
		given(zoneQueryService.getZones()).willReturn(List.of(
			new ZoneResponseDto("seomyeon", "서면", "2623051000", 39710, 39725, 109830, 109850, 0)));

		mockMvc.perform(get("/api/zones").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", hasSize(1)))
			.andExpect(jsonPath("$.data[0].zoneKey").value("seomyeon"))
			.andExpect(jsonPath("$.data[0].name").value("서면"))
			.andExpect(jsonPath("$.data[0].minGridY").value(39710))
			.andExpect(jsonPath("$.data[0].maxGridY").value(39725));
	}

	@Test
	@DisplayName("zones 가 비어있으면 빈 배열 200 이다 (시딩 전)")
	void zones가_비어있으면_빈_배열_200이다() throws Exception {
		given(zoneQueryService.getZones()).willReturn(List.of());

		mockMvc.perform(get("/api/zones").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	@DisplayName("미인증 요청은 401 이다")
	void 미인증_요청은_401이다() throws Exception {
		mockMvc.perform(get("/api/zones"))
			.andExpect(status().isUnauthorized());
	}
}
