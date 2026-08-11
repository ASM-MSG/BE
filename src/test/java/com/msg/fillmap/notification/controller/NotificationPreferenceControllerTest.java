package com.msg.fillmap.notification.controller;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.notification.dto.NotificationPreferenceResponseDto;
import com.msg.fillmap.notification.dto.NotificationPreferenceResponseDto.CategoryPreferenceDto;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.exception.NotificationErrorCode;
import com.msg.fillmap.notification.service.NotificationPreferenceService;
import com.msg.fillmap.user.entity.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("NotificationPreferenceController 알림 설정 조회/토글 (MSG-180)")
class NotificationPreferenceControllerTest {

	private static final long USER_ID = 42L;
	private static final String URL = "/api/notifications/preferences";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private NotificationPreferenceService notificationPreferenceService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	private NotificationPreferenceResponseDto allOnExcept(NotificationCategory offCategory) {
		return new NotificationPreferenceResponseDto(List.of(
			new CategoryPreferenceDto(NotificationCategory.BADGE, NotificationCategory.BADGE != offCategory),
			new CategoryPreferenceDto(NotificationCategory.HOTZONE, NotificationCategory.HOTZONE != offCategory),
			new CategoryPreferenceDto(NotificationCategory.REMIND, NotificationCategory.REMIND != offCategory)));
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("설정을 조회하면 200과 카테고리별 상태를 반환한다 — principal userId 정확값 전달")
	void 설정을_조회하면_200과_카테고리별_상태를_반환한다() throws Exception {
		given(notificationPreferenceService.getPreferences(USER_ID)).willReturn(allOnExcept(null));

		mockMvc.perform(get(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.preferences.length()").value(3))
			.andExpect(jsonPath("$.data.preferences[0].category").value("BADGE"))
			.andExpect(jsonPath("$.data.preferences[0].enabled").value(true))
			.andExpect(jsonPath("$.data.preferences[1].category").value("HOTZONE"))
			.andExpect(jsonPath("$.data.preferences[2].category").value("REMIND"));

		// 정확값 검증 — 토큰의 USER_ID 가 그대로 서비스에 전달돼야 한다(사용자 격리).
		then(notificationPreferenceService).should().getPreferences(USER_ID);
	}

	// 검증: FR-NOTI-06
	@Test
	@DisplayName("카테고리를 토글하면 변경 후 전체 상태를 반환한다 — category·enabled 그대로 전달")
	void 카테고리를_토글하면_변경_후_전체_상태를_반환한다() throws Exception {
		given(notificationPreferenceService.update(USER_ID, "HOTZONE", false))
			.willReturn(allOnExcept(NotificationCategory.HOTZONE));

		mockMvc.perform(patch(URL + "/HOTZONE")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.preferences.length()").value(3))
			.andExpect(jsonPath("$.data.preferences[1].category").value("HOTZONE"))
			.andExpect(jsonPath("$.data.preferences[1].enabled").value(false));

		then(notificationPreferenceService).should().update(USER_ID, "HOTZONE", false);
	}

	@Test
	@DisplayName("enabled 가 없으면 400 이다 — @NotNull global, 서비스 미호출")
	void enabled가_없으면_400이다() throws Exception {
		mockMvc.perform(patch(URL + "/HOTZONE")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));

		then(notificationPreferenceService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("목록 외 카테고리는 10420 이다 — 서비스 스텁 예외의 핸들러 변환 검증")
	void 목록_외_카테고리는_10420이다() throws Exception {
		// 파싱은 서비스 몫 — 10420 을 스텁해 컨트롤러의 예외 전파를 검증한다 (PushTokenControllerTest 선례).
		given(notificationPreferenceService.update(anyLong(), eq("FRIEND"), anyBoolean()))
			.willThrow(new ApiException(NotificationErrorCode.INVALID_CATEGORY));

		mockMvc.perform(patch(URL + "/FRIEND")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":false}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(10420));
	}
}
