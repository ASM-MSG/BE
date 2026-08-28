package com.msg.fillmap.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.auth.dto.DevSocialLoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.oidc.OidcUserInfo;
import com.msg.fillmap.auth.service.OidcLoginService;
import com.msg.fillmap.user.entity.AuthProvider;

@WebMvcTest(DevAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("local")
@DisplayName("DevAuthController [로컬/dev 전용]")
class DevAuthControllerTest {

	private static final String DEV_SOCIAL_LOGIN_URL = "/api/auth/dev/social-login";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private OidcLoginService oidcLoginService;

	@Test
	@DisplayName("성공: (provider, oid)로 액세스+리프레시를 발급하고 X-Device-Id 를 반환한다")
	void socialLogin_success() throws Exception {
		given(oidcLoginService.issueForOidcUser(eq(AuthProvider.KAKAO), any(OidcUserInfo.class), anyString()))
			.willReturn(new LoginResponseDto("access-jwt", "refresh-jwt", "USER"));
		DevSocialLoginRequestDto request = new DevSocialLoginRequestDto("KAKAO", "dev-1", null, null);

		mockMvc.perform(post(DEV_SOCIAL_LOGIN_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").value("access-jwt"))
			.andExpect(jsonPath("$.data.refreshToken").value("refresh-jwt"))
			.andExpect(header().exists("X-Device-Id"));
	}

	@Test
	@DisplayName("provider 를 생략하면 KAKAO 로 처리한다")
	void socialLogin_defaultProvider() throws Exception {
		given(oidcLoginService.issueForOidcUser(eq(AuthProvider.KAKAO), any(OidcUserInfo.class), anyString()))
			.willReturn(new LoginResponseDto("access-jwt", "refresh-jwt", "USER"));
		DevSocialLoginRequestDto request = new DevSocialLoginRequestDto(null, "dev-2", null, null);

		mockMvc.perform(post(DEV_SOCIAL_LOGIN_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isOk());

		verify(oidcLoginService).issueForOidcUser(eq(AuthProvider.KAKAO), any(OidcUserInfo.class), anyString());
	}

	@Test
	@DisplayName("실패: oid 가 비어있으면 400 을 반환하고 서비스는 호출되지 않는다")
	void socialLogin_blankOid() throws Exception {
		DevSocialLoginRequestDto request = new DevSocialLoginRequestDto("KAKAO", "", null, null);

		mockMvc.perform(post(DEV_SOCIAL_LOGIN_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest());

		verify(oidcLoginService, never()).issueForOidcUser(any(), any(), anyString());
	}
}
