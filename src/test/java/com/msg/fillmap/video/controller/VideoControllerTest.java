package com.msg.fillmap.video.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.service.HighlightPreviewService;
import com.msg.fillmap.video.service.VideoService;

@WebMvcTest(VideoController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VideoController")
class VideoControllerTest {

	private static final String UPLOAD_URL = "/api/videos";
	private static final String PRESIGNED_URL = "/api/videos/presigned-url";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private VideoService videoService;

	// 컨트롤러 생성자 의존이라 @WebMvcTest 컨텍스트에 없으면 기동이 깨진다 — 이 파일의 검증엔 미사용 (MSG-351)
	@MockitoBean
	private HighlightPreviewService highlightPreviewService;

	// 검증: FR-VIDEO-01
	@Test
	@DisplayName("duration 이 30 초를 초과하면 400 을 반환하고 서비스는 호출되지 않는다")
	void duration_31이면_400() throws Exception {
		VideoUploadRequestDto request = new VideoUploadRequestDto(
			"videos/pending/1/uuid.mp4", 37.5, 127.0, (short) 31, LocalDateTime.now(), "PRIVATE");

		mockMvc.perform(post(UPLOAD_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest());

		verify(videoService, never()).saveVideo(anyLong(), any());
	}

	@Test
	@DisplayName("presigned URL 요청에 contentLength 가 없으면 400 을 반환하고 서비스는 호출되지 않는다")
	void contentLength_누락이면_400() throws Exception {
		PresignedUrlRequestDto request = new PresignedUrlRequestDto("mp4", "video/mp4", null, null);

		mockMvc.perform(post(PRESIGNED_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest());

		verify(videoService, never()).issuePresignedUrl(anyLong(), any());
	}

	@Test
	@DisplayName("경로변수 타입이 맞지 않으면(비숫자 videoId) 500 이 아니라 400 을 반환한다 — MSG-167 전역 매핑")
	void 경로변수_타입_불일치는_400() throws Exception {
		mockMvc.perform(delete("/api/videos/abc"))
			.andExpect(status().isBadRequest());
	}
}
