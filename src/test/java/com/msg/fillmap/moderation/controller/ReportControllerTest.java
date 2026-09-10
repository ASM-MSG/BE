package com.msg.fillmap.moderation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.msg.fillmap.moderation.dto.ReportCreateResponseDto;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.service.ReportService;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.exception.VideoErrorCode;

/**
 * 영상 신고 접수 컨트롤러 (MSG-192). VideoVisibilityControllerTest 패턴 미러 — TokenProvider 실 Bearer +
 * 서비스 @MockitoBean 이라 DB 를 건드리지 않는다(공유 로컬 DB 에 시드 없음). 검증 축은 서비스 로직이
 * 아니라 HTTP 표면이다: 성공 응답 형상과, 서비스가 던진 ApiException 이 GlobalExceptionHandler 를 거쳐
 * status·developCode 로 나가는 전 구간. 도메인 판정 자체는 ReportIntegrationTest 가 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ReportController 영상 신고 접수")
class ReportControllerTest {

	private static final long USER_ID = 42L;
	private static final long VIDEO_ID = 1042L;
	private static final String URL = "/api/videos/{videoId}/reports";
	private static final String BODY = "{\"reason\":\"INAPPROPRIATE\",\"detail\":null}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private ReportService reportService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: FR-MOD-01
	@Test
	@DisplayName("신고 접수는 200과 reportId·PENDING 을 반환한다 (FR-1)")
	void 신고_접수는_200과_reportId와_PENDING을_반환한다() throws Exception {
		given(reportService.report(anyLong(), anyLong(), any()))
			.willReturn(new ReportCreateResponseDto(17L, "PENDING"));

		mockMvc.perform(post(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.reportId").value(17))
			.andExpect(jsonPath("$.data.status").value("PENDING"));
	}

	// 검증: FR-MOD-04
	@Test
	@DisplayName("존재하지 않는 영상 신고는 404 · developCode 3404 다 — 재생 경로와 같은 존재 은닉 (FR-3)")
	void 존재하지_않는_영상_신고는_404와_3404를_반환한다() throws Exception {
		given(reportService.report(anyLong(), anyLong(), any()))
			.willThrow(new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));

		mockMvc.perform(post(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.developCode").value(3404))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	// 검증: FR-MOD-03
	@Test
	@DisplayName("같은 영상 재신고는 409 · developCode 11409 다 (FR-2)")
	void 같은_영상_재신고는_409와_11409를_반환한다() throws Exception {
		given(reportService.report(anyLong(), anyLong(), any()))
			.willThrow(new ApiException(ReportErrorCode.DUPLICATE_REPORT));

		mockMvc.perform(post(URL, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.developCode").value(11409))
			.andExpect(jsonPath("$.message").value("이미 접수된 신고입니다"));
	}

	@Test
	@DisplayName("인증없이 호출하면 401이다")
	void 인증없이_호출하면_401이다() throws Exception {
		mockMvc.perform(post(URL, VIDEO_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
			.andExpect(status().isUnauthorized());
	}
}
