package com.msg.fillmap.event.controller;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.event.dto.OrgEventCityCountResponseDto;
import com.msg.fillmap.event.dto.OrgEventItemResponseDto;
import com.msg.fillmap.event.dto.OrgEventListResponseDto;
import com.msg.fillmap.event.service.EventQueryService;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 승인 이벤트 목록 HTTP 계약 (MSG-501). 서비스는 목이라 노출 조건·집계·정렬 판정은
 * OrgEventQueryServiceTest 담당이고, 여기서는 컨트롤러 몫만 본다 — 응답 형태, 파라미터 전달, 그리고
 * MSG-496 matcher 가 이 실경로에 실제로 걸리는지다.
 * <p>
 * 인가 테스트만으로는 부족한 이유: 필터가 핸들러 앞에서 거절하므로 컨트롤러가 없거나 경로에 오타가 있어도
 * 401·403 은 그대로 나온다. ORG 200 성공이 실경로 존재를 잡는 시금석이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OrgEventController 승인 이벤트 목록")
class OrgEventControllerTest {

	private static final String URL = "/api/org/events";
	private static final long ORG_ID = 8501L;
	private static final long USER_ID = 8502L;
	private static final long ADMIN_ID = 8503L;
	private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 10, 6, 1, 0);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private EventQueryService eventQueryService;

	private String bearer(long userId, UserRole role) {
		return "Bearer " + tokenProvider.issueAccessToken(userId, role);
	}

	private OrgEventListResponseDto 응답() {
		return new OrgEventListResponseDto(4,
			List.of(new OrgEventCityCountResponseDto("부산", 3), new OrgEventCityCountResponseDto("서울", 1)),
			List.of(new OrgEventItemResponseDto(1L, "부산국제영화제", "부산",
				STARTS_AT, STARTS_AT.plusDays(9), "영화의전당")));
	}

	@Nested
	@DisplayName("성공 실경로")
	class Success {

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("ORG 토큰이면 200 과 목록 필드가 온다")
		void ORG_토큰이면_200과_목록_필드가_온다() throws Exception {
			given(eventQueryService.getApprovedEvents(isNull(), isNull())).willReturn(응답());

			mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(ORG_ID, UserRole.ORG)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.totalCount").value(4))
				.andExpect(jsonPath("$.data.cityCounts[0].cityName").value("부산"))
				.andExpect(jsonPath("$.data.cityCounts[0].count").value(3))
				.andExpect(jsonPath("$.data.events[0].occurrenceId").value(1))
				.andExpect(jsonPath("$.data.events[0].name").value("부산국제영화제"))
				.andExpect(jsonPath("$.data.events[0].startsAt").value("2026-10-06T01:00:00Z"))
				.andExpect(jsonPath("$.data.events[0].placeLabel").value("영화의전당"));
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("시·도와 이름 파라미터가 서비스로 그대로 넘어간다")
		void 시도와_이름_파라미터가_서비스로_그대로_넘어간다() throws Exception {
			given(eventQueryService.getApprovedEvents("부산", "영화")).willReturn(응답());

			mockMvc.perform(get(URL).param("city", "부산").param("name", "영화")
					.header(HttpHeaders.AUTHORIZATION, bearer(ORG_ID, UserRole.ORG)))
				.andExpect(status().isOk());

			then(eventQueryService).should().getApprovedEvents("부산", "영화");
		}
	}

	@Nested
	@DisplayName("인가 회귀 (MSG-496 matcher 실경로)")
	class Authorization {

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("비로그인 호출은 401 이다")
		void 비로그인으로_접근하면_401이다() throws Exception {
			mockMvc.perform(get(URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2403));
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("USER 토큰 호출은 403 이다")
		void USER_토큰으로_접근하면_403이다() throws Exception {
			mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(USER_ID, UserRole.USER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(403));
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("ADMIN 토큰 호출은 403 이다 — ORG 전용이라 관리자에게 열지 않는다")
		void ADMIN_토큰으로_접근하면_403이다() throws Exception {
			mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(403));
		}
	}
}
