package com.msg.fillmap.event.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.event.dto.EventLocationVideoPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoDetailResponseDto;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.service.EventVideoService;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 행사 영상 API 의 인증 계약 (MSG-440 §API 명세 인증 정책). 피드와 상세는 비로그인 열람이고 업로드는
 * 로그인부터다 — 업로드 POST 가 피드 GET 과 <b>같은 경로</b>라, 메서드 무제한 문자열 패턴으로 열면 업로드가
 * 조용히 익명에 풀린다. 서비스는 목이라 이 테스트의 변수는 SecurityConfig 등록뿐이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("행사 영상 공개 접근 (SecurityConfig)")
class EventVideoPublicAccessHttpTest {

	private static final long OCCURRENCE_ID = 12L;
	private static final long LOCATION_ID = 34L;
	private static final long VIDEO_ID = 1042L;
	private static final String FEED_PATH = "/api/event-occurrences/{occurrenceId}/locations/{locationId}/videos";
	private static final String DETAIL_PATH = "/api/event-videos/{videoId}";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EventVideoService eventVideoService;

	private EventVideoDetailResponseDto 상세() {
		return new EventVideoDetailResponseDto(VIDEO_ID, OCCURRENCE_ID, "LIVE", LOCATION_ID, "영화의전당",
			"19422_9582", null, null, "부산광역시 부산진구 부전2동", "https://example.test/playback", (short) 10,
			LocalDateTime.of(2026, 10, 6, 12, 0), LocalDateTime.of(2026, 10, 6, 12, 30), "필맵러", false);
	}

	@Test
	@DisplayName("무인증 피드와 상세는 200으로 성공한다")
	void 무인증_피드와_상세는_200으로_성공한다() throws Exception {
		given(eventVideoService.getLocationVideos(anyLong(), anyLong(), any(), anyInt()))
			.willReturn(new EventLocationVideoPageResponseDto(List.of(), false, null));
		given(eventVideoService.getVideoDetail(anyLong(), any())).willReturn(상세());

		mockMvc.perform(get(FEED_PATH, OCCURRENCE_ID, LOCATION_ID)).andExpect(status().isOk());
		mockMvc.perform(get(DETAIL_PATH, VIDEO_ID)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("무인증 상세 HEAD 는 부수효과 없이 200이다")
	void 무인증_상세_HEAD는_부수효과_없이_200이다() throws Exception {
		mockMvc.perform(head(DETAIL_PATH, VIDEO_ID)).andExpect(status().isOk());

		// 명시 HEAD 매핑이 없으면 GET 핸들러로 폴백해 조회수가 오른다 — 서비스 호출 자체가 없어야 한다.
		then(eventVideoService).should(never()).getVideoDetail(anyLong(), any());
	}

	@Test
	@DisplayName("무인증 업로드는 401로 거절된다 (피드와 같은 경로가 함께 풀리지 않는다)")
	void 무인증_업로드는_401로_거절된다() throws Exception {
		mockMvc.perform(post(FEED_PATH, OCCURRENCE_ID, LOCATION_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"s3Key":"videos/pending/1/x.mp4","durationSec":10,"recordedAt":"2026-10-06T12:00:00Z"}"""))
			.andExpect(status().isUnauthorized());
		then(eventVideoService).should(never()).upload(anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("공개 GET 에서도 무효 토큰은 2401로 거절된다 (토큰 검증 성질 보존)")
	void 공개_GET에서도_무효_토큰은_2401로_거절된다() throws Exception {
		mockMvc.perform(get(DETAIL_PATH, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2401));
	}

	@Test
	@DisplayName("무인증 상세 요청에 다른 영상 경로가 함께 열리지 않는다 (기존 보호 유지)")
	void 무인증_상세_요청에_다른_영상_경로가_함께_열리지_않는다() throws Exception {
		mockMvc.perform(get("/api/videos/{videoId}", VIDEO_ID)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/event-occurrences/{id}/locations/{lid}/videos/{vid}",
			OCCURRENCE_ID, LOCATION_ID, VIDEO_ID)).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("무효 커서 파라미터는 인증 없이도 도메인 코드 13402로 응답한다")
	void 무효_커서_파라미터는_인증_없이도_도메인_코드_13402로_응답한다() throws Exception {
		given(eventVideoService.getLocationVideos(anyLong(), anyLong(), anyString(), anyInt()))
			.willThrow(new ApiException(EventErrorCode.INVALID_CURSOR));

		mockMvc.perform(get(FEED_PATH, OCCURRENCE_ID, LOCATION_ID).param("cursor", "broken"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(13402));
	}
}
