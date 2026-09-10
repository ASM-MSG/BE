package com.msg.fillmap.video.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.dto.GridCoverVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoResponseDto;
import com.msg.fillmap.video.service.VideoService;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GridVideoController")
class GridVideoControllerTest {

	private static final long USER_ID = 42L;
	private static final String GRID_ID = "19422_9582";
	private static final String URL = "/api/grids/{gridId}/my-videos";
	private static final String COVER_URL = "/api/grids/{gridId}/cover";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private VideoService videoService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: FR-COLLECT-11
	@Test
	@DisplayName("격자별 내영상 조회는 200 과 영상배열을 반환한다")
	void 격자별_내영상_조회는_200과_영상배열을_반환한다() throws Exception {
		given(videoService.getGridVideos(anyLong(), eq(GRID_ID))).willReturn(List.of(
			new GridVideoResponseDto(1042L, "https://bucket.s3/thumb.jpg?X-Amz-Signature=abc",
				"READY", (short) 12, LocalDateTime.of(2026, 7, 20, 18, 3, 11)),
			new GridVideoResponseDto(1041L, null, "ENCODING", (short) 8,
				LocalDateTime.of(2026, 7, 20, 17, 55, 2))));

		mockMvc.perform(get(URL, GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data[0].videoId").value(1042))
			.andExpect(jsonPath("$.data[0].thumbnailUrl").value("https://bucket.s3/thumb.jpg?X-Amz-Signature=abc"))
			.andExpect(jsonPath("$.data[0].processingStatus").value("READY"))
			.andExpect(jsonPath("$.data[0].durationSec").value(12))
			.andExpect(jsonPath("$.data[1].thumbnailUrl").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.data[1].processingStatus").value("ENCODING"));
	}

	@Test
	@DisplayName("영상이 없으면 200 과 빈배열을 반환한다")
	void 영상이_없으면_200과_빈배열을_반환한다() throws Exception {
		given(videoService.getGridVideos(anyLong(), eq(GRID_ID))).willReturn(List.of());

		mockMvc.perform(get(URL, GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	@DisplayName("인증없이 호출하면 401 이다")
	void 인증없이_호출하면_401이다() throws Exception {
		mockMvc.perform(get(URL, GRID_ID))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("격자 대표영상 조회는 200과 영상을 반환한다")
	void 격자_대표영상_조회는_200과_영상을_반환한다() throws Exception {
		given(videoService.getGridCover(eq(GRID_ID))).willReturn(
			new GridCoverVideoResponseDto(1042L, "https://bucket.s3/thumb.jpg?X-Amz-Signature=abc",
				(short) 12, 37L, LocalDateTime.of(2026, 7, 20, 18, 3, 11), "busan.vlog"));

		mockMvc.perform(get(COVER_URL, GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videoId").value(1042))
			.andExpect(jsonPath("$.data.thumbnailUrl").value("https://bucket.s3/thumb.jpg?X-Amz-Signature=abc"))
			.andExpect(jsonPath("$.data.durationSec").value(12))
			.andExpect(jsonPath("$.data.viewCount").value(37))
			// MSG-376: 응답 시각은 UTC Z 표기로 나간다 (직렬화 관통 확인 — 정확 일치 + 표기 정규식)
			.andExpect(jsonPath("$.data.recordedAt").value("2026-07-20T18:03:11Z"))
			.andExpect(jsonPath("$.data.recordedAt").value(matchesPattern("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z$")))
			.andExpect(jsonPath("$.data.nickname").value("busan.vlog"))
			.andExpect(jsonPath("$.data.processingStatus").doesNotExist());
	}

	@Test
	@DisplayName("대표가 없으면 200과 body null 을 반환한다")
	void 대표가_없으면_200과_body_null을_반환한다() throws Exception {
		given(videoService.getGridCover(eq(GRID_ID))).willReturn(null);

		mockMvc.perform(get(COVER_URL, GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
	}
}
