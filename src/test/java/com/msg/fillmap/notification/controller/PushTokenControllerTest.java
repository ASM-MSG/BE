package com.msg.fillmap.notification.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.msg.fillmap.notification.dto.PushTokenRequestDto;
import com.msg.fillmap.notification.exception.NotificationErrorCode;
import com.msg.fillmap.notification.service.PushTokenService;
import com.msg.fillmap.user.entity.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("PushTokenController FCM 토큰 등록/해제 (MSG-178)")
class PushTokenControllerTest {

	private static final long USER_ID = 42L;
	private static final String URL = "/api/notifications/tokens";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private PushTokenService pushTokenService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("토큰을 등록하면 200과 body null 을 반환한다 — principal userId 정확값 전달")
	void 토큰을_등록하면_200과_body_null을_반환한다() throws Exception {
		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fcmToken\":\"fcm-token-abc\",\"platform\":\"WEB\",\"appVersion\":\"1.0.0\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body").value(nullValue()));

		// 정확값 검증 — 토큰의 USER_ID 와 요청 DTO 가 그대로 서비스에 전달돼야 한다(사용자 격리).
		then(pushTokenService).should()
			.register(USER_ID, new PushTokenRequestDto("fcm-token-abc", "WEB", "1.0.0"));
	}

	@Test
	@DisplayName("platform 이 목록 외면 9400 이다 — 소문자 ios 는 성공(대소문자 무시), WINDOWS 는 9400")
	void platform이_목록_외면_9400이다() throws Exception {
		// 파싱은 서비스 몫 — 목록 외 platform 에만 9400 을 스텁해 컨트롤러의 예외 전파를 검증한다.
		willThrow(new ApiException(NotificationErrorCode.INVALID_PLATFORM))
			.given(pushTokenService)
			.register(anyLong(), argThat(request -> "WINDOWS".equals(request.platform())));

		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fcmToken\":\"fcm-token-abc\",\"platform\":\"ios\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));

		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fcmToken\":\"fcm-token-abc\",\"platform\":\"WINDOWS\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(9400));
	}

	@Test
	@DisplayName("fcmToken 이 공백이면 400 이다 — @NotBlank global")
	void fcmToken이_공백이면_400이다() throws Exception {
		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fcmToken\":\" \",\"platform\":\"WEB\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}

	@Test
	@DisplayName("fcmToken 이 512자를 넘으면 400 이다 — @Size global")
	void fcmToken이_512자를_넘으면_400이다() throws Exception {
		String tooLong = "a".repeat(513);
		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fcmToken\":\"" + tooLong + "\",\"platform\":\"WEB\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}

	@Test
	@DisplayName("토큰 없이 호출하면 2403 401 이다")
	void 토큰_없이_호출하면_2403_401이다() throws Exception {
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fcmToken\":\"fcm-token-abc\",\"platform\":\"WEB\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}

	@Test
	@DisplayName("해제는 200과 body null 을 반환한다 — 멱등, 서비스에 토큰 그대로 전달")
	void 해제는_200과_body_null을_반환한다() throws Exception {
		mockMvc.perform(delete(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.param("fcmToken", "fcm-token-abc"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body").value(nullValue()));

		then(pushTokenService).should().unregister("fcm-token-abc");
	}

	@Test
	@DisplayName("fcmToken 파라미터 누락 해제는 400 이다 — MissingServletRequestParameter global")
	void fcmToken_파라미터_누락_해제는_400이다() throws Exception {
		mockMvc.perform(delete(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}
}
