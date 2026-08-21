package com.msg.fillmap.event.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.event.dto.EventOccurrenceDetailResponseDto;
import com.msg.fillmap.event.service.EventQueryService;

/**
 * 행사 조회 4종의 인증 계약 (MSG-439 §API 명세 확정 1). 조회는 비로그인 허용이고 로그인은 쓰기부터다.
 * 서비스는 목이라 이 테스트의 변수는 SecurityConfig 등록뿐이다 — 열려야 할 것이 열렸는지와, 같은 경로의
 * 비GET·인접 경로가 함께 열리지 않았는지를 같이 본다(광역 matcher 회귀 방지).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("행사 조회 공개 접근 (SecurityConfig)")
class EventPublicAccessHttpTest {

	private static final long OCCURRENCE_ID = 12L;
	private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 10, 6, 1, 0);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EventQueryService eventQueryService;

	@Test
	@DisplayName("무인증으로 조회 4종이 200으로 성공한다")
	void 무인증으로_조회_4종이_200으로_성공한다() throws Exception {
		given(eventQueryService.getOccurrencesInViewport(any())).willReturn(List.of());
		given(eventQueryService.getOccurrenceDetail(anyLong(), isNull())).willReturn(
			new EventOccurrenceDetailResponseDto(OCCURRENCE_ID, 3L, "부산불꽃축제", STARTS_AT,
				STARTS_AT.plusDays(1), STARTS_AT.plusDays(31), "LIVE", false, List.of()));
		given(eventQueryService.getLocations(anyLong())).willReturn(List.of());
		given(eventQueryService.getLocationsByGrid(anyString())).willReturn(List.of());

		mockMvc.perform(get("/api/event-occurrences")
				.param("swLat", "35.10").param("swLng", "128.90")
				.param("neLat", "35.20").param("neLng", "129.10"))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/event-occurrences/{id}", OCCURRENCE_ID)).andExpect(status().isOk());
		mockMvc.perform(get("/api/event-occurrences/{id}/locations", OCCURRENCE_ID)).andExpect(status().isOk());
		mockMvc.perform(get("/api/grids/{gridId}/event-locations", "19443_9582")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("무인증 비GET 행사 경로는 401로 거절된다 (쓰기 API 선반영 방지)")
	void 무인증_비GET_행사_경로는_401로_거절된다() throws Exception {
		mockMvc.perform(post("/api/event-occurrences/{id}/locations", OCCURRENCE_ID))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("무인증 격자 단일 조회는 여전히 401이다 (기존 보호 유지)")
	void 무인증_격자_단일_조회는_여전히_401이다() throws Exception {
		mockMvc.perform(get("/api/grids/{gridId}", "19443_9582"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("공개 GET에서도 무효 토큰은 2401로 거절된다 (토큰 검증 성질 보존)")
	void 공개_GET에서도_무효_토큰은_2401로_거절된다() throws Exception {
		mockMvc.perform(get("/api/event-occurrences/{id}", OCCURRENCE_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2401));
	}
}
