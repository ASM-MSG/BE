package com.msg.fillmap.moderation.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.moderation.dto.AdminReportItemResponseDto;
import com.msg.fillmap.moderation.dto.AdminReportListResponseDto;
import com.msg.fillmap.moderation.dto.AdminReportProcessResponseDto;
import com.msg.fillmap.moderation.dto.AdminVideoReviewResponseDto;
import com.msg.fillmap.moderation.dto.AdminVideoUnblindResponseDto;
import com.msg.fillmap.moderation.entity.ReportReason;
import com.msg.fillmap.moderation.entity.ReportStatus;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.service.AdminReportService;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;

/**
 * 관리자 신고 처리 컨트롤러 (MSG-195). ReportControllerTest 패턴 미러 — TokenProvider 실 Bearer +
 * 서비스 @MockitoBean 이라 DB 를 건드리지 않는다. 검증 축은 도메인 판정이 아니라 HTTP 표면이다:
 * 파라미터 기본값·바인딩, 응답 형상, 서비스가 던진 ApiException 의 status·developCode 변환.
 * 도메인 판정 자체는 AdminReportIntegrationTest 가, 인가는 AdminAuthorizationTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AdminReportController 관리자 신고 처리")
class AdminReportControllerTest {

	private static final long ADMIN_ID = 8801L;
	private static final String REPORTS_URL = "/api/admin/reports";
	private static final String VIDEOS_URL = "/api/admin/videos";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private AdminReportService adminReportService;

	private String adminBearer() {
		return "Bearer " + tokenProvider.issueAccessToken(ADMIN_ID, UserRole.ADMIN);
	}

	private AdminReportListResponseDto onePendingItem() {
		return new AdminReportListResponseDto(
			List.of(new AdminReportItemResponseDto(
				7L, ReportStatus.PENDING, ReportReason.INAPPROPRIATE, null,
				LocalDateTime.of(2026, 8, 6, 10, 15), 3L, "정민",
				1042L, VideoStatus.ACTIVE, "성민", null, null)),
			0, 20, 1, 1);
	}

	@Test
	@DisplayName("신고 목록 조회는 200과 항목·페이지 정보를 반환한다 (FR-1, FR-2)")
	void 신고_목록_조회는_200과_항목과_페이지_정보를_반환한다() throws Exception {
		given(adminReportService.getReports(anyString(), anyInt(), anyInt())).willReturn(onePendingItem());

		mockMvc.perform(get(REPORTS_URL).header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.items[0].reportId").value(7))
			.andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
			.andExpect(jsonPath("$.data.items[0].reason").value("INAPPROPRIATE"))
			.andExpect(jsonPath("$.data.items[0].reporterNickname").value("정민"))
			.andExpect(jsonPath("$.data.items[0].videoStatus").value("ACTIVE"))
			.andExpect(jsonPath("$.data.items[0].videoOwnerNickname").value("성민"))
			.andExpect(jsonPath("$.data.items[0].reviewedBy").doesNotExist())
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.totalPages").value(1));
	}

	@Test
	@DisplayName("파라미터를 생략하면 PENDING·page 0·size 20 으로 조회한다 (API 명세 기본값)")
	void 파라미터를_생략하면_PENDING과_page0과_size20으로_조회한다() throws Exception {
		given(adminReportService.getReports(anyString(), anyInt(), anyInt())).willReturn(onePendingItem());

		mockMvc.perform(get(REPORTS_URL).header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isOk());

		then(adminReportService).should().getReports("PENDING", 0, 20);
	}

	@Test
	@DisplayName("status·page·size 를 보내면 그대로 서비스에 전달된다")
	void status와_page와_size를_보내면_그대로_서비스에_전달된다() throws Exception {
		given(adminReportService.getReports(anyString(), anyInt(), anyInt())).willReturn(onePendingItem());

		mockMvc.perform(get(REPORTS_URL)
				.param("status", "resolved")
				.param("page", "2")
				.param("size", "5")
				.header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isOk());

		then(adminReportService).should().getReports("resolved", 2, 5);
	}

	@Test
	@DisplayName("지원하지 않는 상태 값은 400 · developCode 11420 이다")
	void 지원하지_않는_상태_값은_400과_11420을_반환한다() throws Exception {
		given(adminReportService.getReports(eq("BOGUS"), anyInt(), anyInt()))
			.willThrow(new ApiException(ReportErrorCode.INVALID_STATUS_FILTER));

		mockMvc.perform(get(REPORTS_URL)
				.param("status", "BOGUS")
				.header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(11420))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	@DisplayName("page·size 범위 위반은 400 · developCode 11421 이다")
	void page와_size_범위_위반은_400과_11421을_반환한다() throws Exception {
		given(adminReportService.getReports(anyString(), anyInt(), anyInt()))
			.willThrow(new ApiException(ReportErrorCode.INVALID_PAGE_REQUEST));

		mockMvc.perform(get(REPORTS_URL)
				.param("size", "101")
				.header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(11421));
	}

	@Test
	@DisplayName("숫자가 아닌 page 는 400 이다 — 타입 불일치 (500 회귀 방지)")
	void 숫자가_아닌_page는_400이다() throws Exception {
		mockMvc.perform(get(REPORTS_URL)
				.param("page", "abc")
				.header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}

	@Test
	@DisplayName("승인은 200 과 처리 결과를 반환하고, 처리자는 토큰의 사용자 id 다 (FR-4)")
	void 승인은_200과_처리_결과를_반환한다() throws Exception {
		given(adminReportService.approve(anyLong(), anyLong())).willReturn(
			new AdminReportProcessResponseDto(7L, ReportStatus.RESOLVED, 1042L, VideoStatus.BLINDED,
				LocalDateTime.of(2026, 8, 6, 11, 0)));

		mockMvc.perform(post(REPORTS_URL + "/7/approve").header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.reportId").value(7))
			.andExpect(jsonPath("$.data.status").value("RESOLVED"))
			.andExpect(jsonPath("$.data.videoId").value(1042))
			.andExpect(jsonPath("$.data.videoStatus").value("BLINDED"))
			.andExpect(jsonPath("$.data.reviewedAt").exists());

		// @AuthenticationPrincipal 배선 확인 — 처리자 id 는 토큰 주체다.
		then(adminReportService).should().approve(ADMIN_ID, 7L);
	}

	@Test
	@DisplayName("기각은 200 과 REJECTED, 손대지 않은 영상 상태를 반환한다 (FR-6)")
	void 기각은_200과_REJECTED를_반환한다() throws Exception {
		given(adminReportService.reject(anyLong(), anyLong())).willReturn(
			new AdminReportProcessResponseDto(7L, ReportStatus.REJECTED, 1042L, VideoStatus.ACTIVE,
				LocalDateTime.of(2026, 8, 6, 11, 0)));

		mockMvc.perform(post(REPORTS_URL + "/7/reject").header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.status").value("REJECTED"))
			.andExpect(jsonPath("$.data.videoStatus").value("ACTIVE"));

		then(adminReportService).should().reject(ADMIN_ID, 7L);
	}

	@Test
	@DisplayName("없는 신고의 처리는 404 · developCode 11404 다")
	void 없는_신고의_처리는_404와_11404를_반환한다() throws Exception {
		given(adminReportService.approve(anyLong(), anyLong()))
			.willThrow(new ApiException(ReportErrorCode.REPORT_NOT_FOUND));

		mockMvc.perform(post(REPORTS_URL + "/999/approve").header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.developCode").value(11404))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	@DisplayName("이미 처리된 신고는 409 · developCode 11410 이다")
	void 이미_처리된_신고는_409와_11410을_반환한다() throws Exception {
		given(adminReportService.reject(anyLong(), anyLong()))
			.willThrow(new ApiException(ReportErrorCode.ALREADY_PROCESSED_REPORT));

		mockMvc.perform(post(REPORTS_URL + "/7/reject").header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.developCode").value(11410))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	@DisplayName("블라인드 해제는 200 과 ACTIVE 를 반환한다 (FR-8)")
	void 블라인드_해제는_200과_ACTIVE를_반환한다() throws Exception {
		given(adminReportService.unblindVideo(anyLong()))
			.willReturn(new AdminVideoUnblindResponseDto(1042L, VideoStatus.ACTIVE));

		mockMvc.perform(post(VIDEOS_URL + "/1042/unblind").header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videoId").value(1042))
			.andExpect(jsonPath("$.data.status").value("ACTIVE"));

		then(adminReportService).should().unblindVideo(1042L);
	}

	@Test
	@DisplayName("이미 ACTIVE 인 영상의 해제는 409 · developCode 3409 다")
	void 이미_ACTIVE인_영상의_해제는_409와_3409를_반환한다() throws Exception {
		given(adminReportService.unblindVideo(anyLong()))
			.willThrow(new ApiException(VideoErrorCode.ALREADY_IN_TARGET_STATUS));

		mockMvc.perform(post(VIDEOS_URL + "/1042/unblind").header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.developCode").value(3409))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	@DisplayName("단건 확인은 200 과 영상 메타·재생 URL 을 반환한다 (FR-3)")
	void 단건_확인은_200과_영상_메타와_재생_URL을_반환한다() throws Exception {
		given(adminReportService.getVideoForReview(anyLong())).willReturn(new AdminVideoReviewResponseDto(
			1042L, VideoStatus.BLINDED, ProcessingStatus.READY, Visibility.PRIVATE, (short) 12,
			LocalDateTime.of(2026, 7, 20, 18, 3, 11), "https://signed/play", "https://signed/thumb", 600L));

		mockMvc.perform(get(VIDEOS_URL + "/1042").header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videoId").value(1042))
			.andExpect(jsonPath("$.data.status").value("BLINDED"))
			.andExpect(jsonPath("$.data.processingStatus").value("READY"))
			.andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
			.andExpect(jsonPath("$.data.durationSec").value(12))
			.andExpect(jsonPath("$.data.playbackUrl").value("https://signed/play"))
			.andExpect(jsonPath("$.data.thumbnailUrl").value("https://signed/thumb"))
			.andExpect(jsonPath("$.data.expiresInSec").value(600));

		then(adminReportService).should().getVideoForReview(1042L);
	}

	@Test
	@DisplayName("없는 영상의 확인은 404 · developCode 3404 다")
	void 없는_영상의_확인은_404와_3404를_반환한다() throws Exception {
		given(adminReportService.getVideoForReview(anyLong()))
			.willThrow(new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));

		mockMvc.perform(get(VIDEOS_URL + "/999").header(HttpHeaders.AUTHORIZATION, adminBearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.developCode").value(3404))
			.andExpect(jsonPath("$.data").doesNotExist());
	}
}
