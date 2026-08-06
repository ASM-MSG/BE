package com.msg.fillmap.moderation.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.moderation.dto.AdminReportItemResponseDto;
import com.msg.fillmap.moderation.dto.AdminReportListResponseDto;
import com.msg.fillmap.moderation.entity.ReportReason;
import com.msg.fillmap.moderation.entity.ReportStatus;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.service.AdminReportService;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.entity.VideoStatus;

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
}
