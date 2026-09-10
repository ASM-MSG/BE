package com.msg.fillmap.video.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.dto.HighlightPreviewResponseDto;
import com.msg.fillmap.video.service.HighlightPreviewService;

/**
 * 하이라이트 선분석 엔드포인트의 직렬화 계약 검증 (MSG-351) — [[시작초, 끝초], ...] 중첩 배열과
 * 빈 배열(추천 없음, null 아님)이 그대로 내려가는지. VideoPlaybackControllerTest 와 같은
 * @SpringBootTest + 실 TokenProvider 방식 — @WebMvcTest(addFilters=false)로는 principal 200 경로가 안 선다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("VideoController 하이라이트 선분석")
class HighlightPreviewControllerTest {

	private static final long USER_ID = 42L;
	private static final String URL = "/api/videos/highlight-preview";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private HighlightPreviewService highlightPreviewService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: FR-MEDIA-11
	@Test
	@DisplayName("선분석 200 응답에 중첩 구간 배열이 그대로 직렬화된다")
	void 선분석_200_응답에_중첩_구간_배열이_그대로_직렬화된다() throws Exception {
		given(highlightPreviewService.analyze(anyLong(), any())).willReturn(
			new HighlightPreviewResponseDto(List.of(List.of(0.0, 5.12), List.of(10.0, 16.4))));

		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"s3Key\":\"videos/pending/42/uuid.mp4\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.highlights.length()").value(2))
			.andExpect(jsonPath("$.data.highlights[0][0]").value(0.0))
			.andExpect(jsonPath("$.data.highlights[0][1]").value(5.12))
			.andExpect(jsonPath("$.data.highlights[1][0]").value(10.0));
	}

	// 검증: FR-MEDIA-12
	@Test
	@DisplayName("추천이 없으면 null 이 아니라 빈 배열이 내려간다 — FR-4, FE 는 추천 단계 스킵")
	void 추천이_없으면_빈_배열이_그대로_내려간다() throws Exception {
		given(highlightPreviewService.analyze(anyLong(), any()))
			.willReturn(new HighlightPreviewResponseDto(List.of()));

		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"s3Key\":\"videos/pending/42/uuid.mp4\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.highlights").isArray())
			.andExpect(jsonPath("$.data.highlights.length()").value(0));
	}
}
