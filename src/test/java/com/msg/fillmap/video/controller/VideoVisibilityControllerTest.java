package com.msg.fillmap.video.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.dto.VideoVisibilityResponseDto;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.service.VideoService;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("VideoController 공개 범위 전환")
class VideoVisibilityControllerTest {

	private static final long USER_ID = 42L;
	private static final long VIDEO_ID = 1042L;
	private static final String URL = "/api/videos/{videoId}/visibility";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private VideoService videoService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: FR-VIDEO-15
	@Test
	@DisplayName("visibility 전환은 200과 전환된 상태를 반환한다")
	void visibility_전환은_200과_전환된_상태를_반환한다() throws Exception {
		given(videoService.setVisibility(anyLong(), anyLong(), any()))
			.willReturn(new VideoVisibilityResponseDto(VIDEO_ID, "PUBLIC"));

		mockMvc.perform(patch(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"PUBLIC\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videoId").value(1042))
			.andExpect(jsonPath("$.data.visibility").value("PUBLIC"));
	}

	// 검증: FR-VIDEO-15
	@Test
	@DisplayName("잘못된 visibility 값은 500이 아니라 400이다 — 역직렬화 500 회귀 방지")
	void 잘못된_visibility_값은_500이_아니라_400이다() throws Exception {
		// 필드를 String 으로 받아 서비스에서 파싱하므로 미지 값도 역직렬화는 성공하고 INVALID_VISIBILITY(400)가 된다.
		// enum 으로 받았다면 여기서 역직렬화 실패 → 500 이 났을 자리다.
		given(videoService.setVisibility(anyLong(), anyLong(), any()))
			.willThrow(new ApiException(VideoErrorCode.INVALID_VISIBILITY));

		mockMvc.perform(patch(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"BOGUS\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("타인 영상 전환은 403이다")
	void 타인_영상_전환은_403이다() throws Exception {
		given(videoService.setVisibility(anyLong(), anyLong(), any()))
			.willThrow(new ApiException(VideoErrorCode.VIDEO_FORBIDDEN));

		mockMvc.perform(patch(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"PUBLIC\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("인증없이 호출하면 401이다")
	void 인증없이_호출하면_401이다() throws Exception {
		mockMvc.perform(patch(URL, VIDEO_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"visibility\":\"PUBLIC\"}"))
			.andExpect(status().isUnauthorized());
	}
}
