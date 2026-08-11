package com.msg.fillmap.video.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
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
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.dto.VideoPlaybackResponseDto;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.service.VideoService;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("VideoController 단건 재생 조회")
class VideoPlaybackControllerTest {

	private static final long USER_ID = 42L;
	private static final long VIDEO_ID = 1042L;
	private static final String URL = "/api/videos/{videoId}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private VideoService videoService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("단건 조회는 200과 재생메타를 반환한다")
	void 단건_조회는_200과_재생메타를_반환한다() throws Exception {
		given(videoService.getVideoPlayback(anyLong(), anyLong())).willReturn(new VideoPlaybackResponseDto(
			VIDEO_ID, "https://bucket.s3/play.mp4?X-Amz-Signature=abc",
			"https://bucket.s3/thumb.jpg?X-Amz-Signature=def", "19422_9582", (short) 12,
			"READY", "PUBLIC", "ACTIVE", 37L, LocalDateTime.of(2026, 7, 20, 18, 3, 11), 600L,
			"서면", "I-9", "서울특별시 강남구 역삼1동", null, "busan.vlog"));

		mockMvc.perform(get(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videoId").value(1042))
			.andExpect(jsonPath("$.data.playbackUrl").value("https://bucket.s3/play.mp4?X-Amz-Signature=abc"))
			.andExpect(jsonPath("$.data.processingStatus").value("READY"))
			.andExpect(jsonPath("$.data.status").value("ACTIVE"))
			.andExpect(jsonPath("$.data.viewCount").value(37))
			.andExpect(jsonPath("$.data.expiresInSec").value(600))
			.andExpect(jsonPath("$.data.zoneName").value("서면"))
			.andExpect(jsonPath("$.data.zoneCell").value("I-9"))
			.andExpect(jsonPath("$.data.regionName").value("서울특별시 강남구 역삼1동"))
			.andExpect(jsonPath("$.data.nickname").value("busan.vlog"));
	}

	@Test
	@DisplayName("단건 조회 응답에 highlights 배열이 실린다")
	void 단건_조회_응답에_highlights_배열이_실린다() throws Exception {
		// record 컴포넌트 추가가 Jackson 직렬화까지 이어지는지 — [[시작초, 끝초], ...] 중첩 배열 형태를 통합 레벨에서 본다 (MSG-350).
		given(videoService.getVideoPlayback(anyLong(), anyLong())).willReturn(new VideoPlaybackResponseDto(
			VIDEO_ID, "https://bucket.s3/play.mp4?X-Amz-Signature=abc",
			"https://bucket.s3/thumb.jpg?X-Amz-Signature=def", "19422_9582", (short) 12,
			"READY", "PUBLIC", "ACTIVE", 37L, LocalDateTime.of(2026, 7, 20, 18, 3, 11), 600L,
			"서면", "I-9", "서울특별시 강남구 역삼1동",
			List.of(List.of(0.0, 4.25), List.of(12.0, 18.5), List.of(20.0, 27.5)), "busan.vlog"));

		mockMvc.perform(get(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.highlights.length()").value(3))
			.andExpect(jsonPath("$.data.highlights[0][0]").value(0.0))
			.andExpect(jsonPath("$.data.highlights[0][1]").value(4.25))
			.andExpect(jsonPath("$.data.highlights[2][1]").value(27.5));
	}

	@Test
	@DisplayName("타인 비공개 영상 조회는 403이다")
	void 타인_비공개_영상_조회는_403이다() throws Exception {
		given(videoService.getVideoPlayback(anyLong(), anyLong()))
			.willThrow(new ApiException(VideoErrorCode.VIDEO_FORBIDDEN));

		mockMvc.perform(get(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("없는 영상 조회는 404다")
	void 없는_영상_조회는_404다() throws Exception {
		given(videoService.getVideoPlayback(anyLong(), anyLong()))
			.willThrow(new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));

		mockMvc.perform(get(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("인증없이 호출하면 401이다")
	void 인증없이_호출하면_401이다() throws Exception {
		mockMvc.perform(get(URL, VIDEO_ID))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("HEAD 요청은 조회수 증가 없이 200을 반환한다")
	void HEAD_요청은_조회수_증가_없이_200을_반환한다() throws Exception {
		// 명시 HEAD 매핑이 GET 폴백을 가로채 서비스를 안 탄다 → view_count 부작용 없음(Codex R2).
		mockMvc.perform(head(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk());

		verify(videoService, never()).getVideoPlayback(anyLong(), anyLong());
	}
}
