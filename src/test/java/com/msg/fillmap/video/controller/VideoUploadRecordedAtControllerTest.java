package com.msg.fillmap.video.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.service.HighlightPreviewService;
import com.msg.fillmap.video.service.VideoService;

/**
 * 업로드 확정 요청의 recordedAt 역직렬화가 실제 HTTP 경로로 관통하는지 검증한다 (MSG-376 FR-4).
 * 매퍼 단위 계약 테스트({@code UtcLocalDateTimeJsonCodecTest})는 컨트롤러 앞 경로를 못 보기 때문이다.
 * VideoControllerTest 가 아니라 별도 파일인 이유는 그쪽이 @WebMvcTest(addFilters=false) 슬라이스라
 * 인증 주체가 필요한 200 경로를 세울 수 없어서다 (HighlightPreviewControllerTest 와 같은 선례).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("VideoController 업로드 확정 recordedAt")
class VideoUploadRecordedAtControllerTest {

	private static final long USER_ID = 42L;
	private static final String URL = "/api/videos";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private VideoService videoService;

	@MockitoBean
	private HighlightPreviewService highlightPreviewService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: MSG-376 — 오프셋 입력의 UTC 환산 관통 (PRD 기능 요구 4, SRS 등재는 NFR DATA 06)
	@Test
	@DisplayName("recordedAt 에 +09:00 오프셋을 담아 보내면 UTC 로 환산돼 서비스에 전달된다")
	void 오프셋_입력_recordedAt은_UTC로_환산되어_서비스에_전달된다() throws Exception {
		given(videoService.saveVideo(anyLong(), any()))
			.willReturn(new VideoUploadResponseDto(1042L, "19443_9582", "UPLOADED", true,
				List.of(), List.of(), null, null, null));
		String body = """
			{"s3Key":"videos/pending/42/uuid.mp4","lat":37.5,"lng":127.0,"durationSec":15,\
			"recordedAt":"2026-08-11T14:55:21+09:00","visibility":"PRIVATE"}""";

		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk());

		ArgumentCaptor<VideoUploadRequestDto> captor = ArgumentCaptor.forClass(VideoUploadRequestDto.class);
		verify(videoService).saveVideo(anyLong(), captor.capture());
		assertThat(captor.getValue().recordedAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 5, 55, 21));
	}

	// 검증: MSG-376 — 형식 오류는 신규 에러 코드 없이 기존 말폼드 본문 경로(400)를 그대로 탄다
	@ParameterizedTest(name = "recordedAt = {0}")
	@ValueSource(strings = {"\"어제 오후\"", "{}"})
	@DisplayName("recordedAt 이 시각 문자열이 아니면 500 이 아니라 400 이다")
	void 시각_문자열이_아닌_recordedAt은_400이다(String recordedAt) throws Exception {
		String body = """
			{"s3Key":"videos/pending/42/uuid.mp4","lat":37.5,"lng":127.0,"durationSec":15,\
			"recordedAt":%s,"visibility":"PRIVATE"}""".formatted(recordedAt);

		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}
}
