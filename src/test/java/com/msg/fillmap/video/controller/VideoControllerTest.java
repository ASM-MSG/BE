package com.msg.fillmap.video.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.service.VideoService;

@WebMvcTest(VideoController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VideoController")
class VideoControllerTest {

	private static final String UPLOAD_URL = "/api/videos";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private VideoService videoService;

	@Test
	@DisplayName("duration 이 30 초를 초과하면 400 을 반환하고 서비스는 호출되지 않는다")
	void duration_31이면_400() throws Exception {
		VideoUploadRequestDto request = new VideoUploadRequestDto(
			"videos/original/1/uuid.mp4", 37.5, 127.0, (short) 31, LocalDateTime.now());

		mockMvc.perform(post(UPLOAD_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest());

		verify(videoService, never()).saveVideo(anyLong(), any());
	}
}
