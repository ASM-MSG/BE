package com.msg.fillmap.event.controller;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.event.dto.GridEventLocationResponseDto;
import com.msg.fillmap.event.service.EventQueryService;

/**
 * 격자 역조회 HTTP 계약 (MSG-439 API 4). 정렬·노출 판정은 EventQueryServiceTest 담당이고 여기서는
 * 경로 파라미터가 서비스로 그대로 전달되는지와 응답 매핑만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("EventLocationController")
class EventLocationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EventQueryService eventQueryService;

	@Test
	@DisplayName("격자가 속한 위치와 표시명 재료가 배열로 반환된다")
	void 격자_역조회는_위치와_표시명_재료를_반환한다() throws Exception {
		given(eventQueryService.getLocationsByGrid("19443_9582")).willReturn(List.of(
			new GridEventLocationResponseDto(12L, "부산불꽃축제", "LIVE", 31L, "부산역 팝업",
				"19443_9582", "서면", "A-14", "부전동")));

		mockMvc.perform(get("/api/grids/{gridId}/event-locations", "19443_9582"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data[0].occurrenceId").value(12))
			.andExpect(jsonPath("$.data[0].occurrenceStatus").value("LIVE"))
			.andExpect(jsonPath("$.data[0].locationId").value(31))
			.andExpect(jsonPath("$.data[0].representativeGridId").value("19443_9582"))
			.andExpect(jsonPath("$.data[0].regionName").value("부전동"));
	}

	@Test
	@DisplayName("격자 포맷이 아닌 임의 문자열도 200 빈 배열이다")
	void 격자_포맷이_아닌_임의_문자열도_빈_배열이다() throws Exception {
		given(eventQueryService.getLocationsByGrid("not-a-grid-id")).willReturn(List.of());

		mockMvc.perform(get("/api/grids/{gridId}/event-locations", "not-a-grid-id"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(0));
	}
}
