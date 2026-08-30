package com.msg.fillmap.event.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.event.dto.EventLocationResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceChipResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceDetailResponseDto;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.service.EventQueryService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 행사 회차 조회 HTTP 계약 (MSG-439 API 1·2·3). 서비스는 목이라 상태·정렬·표시명 판정은
 * EventQueryServiceTest 담당이고, 여기서는 컨트롤러 몫만 본다 — bbox 누락의 도메인 코드 변환, principal 을
 * 서비스로 넘기는 시그니처, 도메인 예외의 HTTP 응답 변환이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("EventOccurrenceController")
class EventOccurrenceControllerTest {

	private static final long USER_ID = 7439L;
	private static final long OCCURRENCE_ID = 12L;
	private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 10, 6, 1, 0);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private EventQueryService eventQueryService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	private MockHttpServletRequestBuilder occurrences() {
		return get("/api/event-occurrences")
			.param("swLat", "35.10").param("swLng", "128.90")
			.param("neLat", "35.20").param("neLng", "129.10");
	}

	private EventOccurrenceDetailResponseDto detail() {
		return new EventOccurrenceDetailResponseDto(OCCURRENCE_ID, 3L, "부산불꽃축제", STARTS_AT,
			STARTS_AT.plusDays(1), STARTS_AT.plusDays(31), "LIVE", false, List.of());
	}

	@Nested
	@DisplayName("뷰포트 목록")
	class Viewport {

		@Test
		@DisplayName("서비스 결과를 그대로 배열로 반환한다")
		void 뷰포트_목록은_서비스_결과를_배열로_반환한다() throws Exception {
			given(eventQueryService.getOccurrencesInViewport(any())).willReturn(List.of(
				new EventOccurrenceChipResponseDto(OCCURRENCE_ID, "부산불꽃축제", "부산",
					STARTS_AT, STARTS_AT.plusDays(1), "LIVE")));

			mockMvc.perform(occurrences())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data[0].occurrenceId").value(OCCURRENCE_ID))
				.andExpect(jsonPath("$.data[0].cityName").value("부산"))
				.andExpect(jsonPath("$.data[0].status").value("LIVE"));
		}

		@Test
		@DisplayName("뷰포트 파라미터가 하나라도 없으면 13400으로 거절된다")
		void 뷰포트_파라미터가_하나라도_없으면_13400으로_거절된다() throws Exception {
			mockMvc.perform(get("/api/event-occurrences")
					.param("swLat", "35.10").param("swLng", "128.90").param("neLat", "35.20"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(13400));

			then(eventQueryService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("회차 상세")
	class Detail {

		@Test
		@DisplayName("로그인 상세 조회는 인증 사용자 id를 서비스에 전달한다")
		void 로그인_상세_조회는_인증_사용자_id를_서비스에_전달한다() throws Exception {
			given(eventQueryService.getOccurrenceDetail(eq(OCCURRENCE_ID), eq(USER_ID))).willReturn(detail());

			mockMvc.perform(get("/api/event-occurrences/{id}", OCCURRENCE_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.occurrenceId").value(OCCURRENCE_ID))
				.andExpect(jsonPath("$.data.notificationOn").value(false));

			then(eventQueryService).should().getOccurrenceDetail(OCCURRENCE_ID, USER_ID);
		}

		@Test
		@DisplayName("비로그인 상세 조회는 사용자 id 자리에 null을 전달한다")
		void 비로그인_상세_조회는_null을_전달한다() throws Exception {
			given(eventQueryService.getOccurrenceDetail(eq(OCCURRENCE_ID), isNull())).willReturn(detail());

			mockMvc.perform(get("/api/event-occurrences/{id}", OCCURRENCE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notificationOn").value(false));

			then(eventQueryService).should().getOccurrenceDetail(OCCURRENCE_ID, null);
		}

		@Test
		@DisplayName("서비스가 던진 13404는 404 응답으로 변환된다")
		void 없는_회차_상세는_13404로_응답한다() throws Exception {
			willThrow(new ApiException(EventErrorCode.EVENT_NOT_FOUND))
				.given(eventQueryService).getOccurrenceDetail(eq(999L), isNull());

			mockMvc.perform(get("/api/event-occurrences/{id}", 999L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(13404))
				.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
		}
	}

	@Nested
	@DisplayName("위치 목록")
	class Locations {

		@Test
		@DisplayName("표시명 재료와 영상 수가 응답에 그대로 실린다")
		void 위치_목록_응답에_표시명_재료가_실린다() throws Exception {
			given(eventQueryService.getLocations(OCCURRENCE_ID)).willReturn(List.of(
				new EventLocationResponseDto(31L, "부산역 팝업", "POPUP", "11:00 ~ 20:00",
					List.of("19443_9582", "19443_9583"), "19443_9582", "서면", "A-14", "부전동", 7L,
					null, null, null, null, null, null)));

			mockMvc.perform(get("/api/event-occurrences/{id}/locations", OCCURRENCE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].locationId").value(31))
				.andExpect(jsonPath("$.data[0].gridIds.length()").value(2))
				.andExpect(jsonPath("$.data[0].representativeGridId").value("19443_9582"))
				.andExpect(jsonPath("$.data[0].zoneName").value("서면"))
				.andExpect(jsonPath("$.data[0].zoneCell").value("A-14"))
				.andExpect(jsonPath("$.data[0].regionName").value("부전동"))
				.andExpect(jsonPath("$.data[0].videoCount").value(7));
		}

		@Test
		@DisplayName("위치가 없는 회차는 빈 배열 200이다")
		void 위치가_없는_회차는_빈_배열_200이다() throws Exception {
			given(eventQueryService.getLocations(OCCURRENCE_ID)).willReturn(List.of());

			mockMvc.perform(get("/api/event-occurrences/{id}/locations", OCCURRENCE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0));
		}
	}
}
