package com.msg.fillmap.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.OidcLoginRequestDto;
import com.msg.fillmap.auth.dto.LoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.SignupRequestDto;
import com.msg.fillmap.auth.dto.SignupResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.service.AuthService;
import com.msg.fillmap.auth.service.OidcLoginService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.exception.UserErrorCode;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AuthController")
class AuthControllerTest {

	private static final String SIGNUP_URL = "/auth/signup";
	private static final String LOGIN_URL = "/auth/login";
	private static final String LOGOUT_URL = "/auth/logout";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private OidcLoginService oidcLoginService;

	@Nested
	@DisplayName("POST /auth/signup")
	class Signup {

		@Test
		@DisplayName("성공: 정상 요청이면 200 과 SuccessResponse 형식의 응답 DTO 를 반환한다")
		void signup_success() throws Exception {
			SignupRequestDto request = new SignupRequestDto(
				"test@example.com", "password123", "테스터"
			);
			SignupResponseDto response = new SignupResponseDto(
				1L, "test@example.com", "테스터", LocalDateTime.now()
			);
			given(authService.signup(any(SignupRequestDto.class))).willReturn(response);

			mockMvc.perform(post(SIGNUP_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.body.id").value(1))
				.andExpect(jsonPath("$.body.email").value("test@example.com"))
				.andExpect(jsonPath("$.body.nickname").value("테스터"));
		}

		@Test
		@DisplayName("실패: 이메일 형식이 잘못되면 400 을 반환하고 서비스는 호출되지 않는다")
		void signup_invalidEmail() throws Exception {
			SignupRequestDto request = new SignupRequestDto(
				"invalid-email", "password123", "테스터"
			);

			mockMvc.perform(post(SIGNUP_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));

			verify(authService, never()).signup(any());
		}

		@Test
		@DisplayName("실패: 비밀번호가 8자 미만이면 400 을 반환한다")
		void signup_shortPassword() throws Exception {
			SignupRequestDto request = new SignupRequestDto(
				"test@example.com", "short1", "테스터"
			);

			mockMvc.perform(post(SIGNUP_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());

			verify(authService, never()).signup(any());
		}

		@Test
		@DisplayName("실패: 닉네임이 비어있으면 400 을 반환한다")
		void signup_blankNickname() throws Exception {
			SignupRequestDto request = new SignupRequestDto(
				"test@example.com", "password123", ""
			);

			mockMvc.perform(post(SIGNUP_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());

			verify(authService, never()).signup(any());
		}

		@Test
		@DisplayName("실패: 이메일이 이미 존재하면 409 CONFLICT 와 EMAIL_ALREADY_EXISTS 코드를 반환한다")
		void signup_duplicateEmail() throws Exception {
			SignupRequestDto request = new SignupRequestDto(
				"test@example.com", "password123", "테스터"
			);
			given(authService.signup(any(SignupRequestDto.class)))
				.willThrow(new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS));

			mockMvc.perform(post(SIGNUP_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1409))
				.andExpect(jsonPath("$.message").value("이미 사용 중인 이메일입니다"));
		}
	}

	@Nested
	@DisplayName("POST /auth/oauth/{provider}")
	class OauthLogin {

		@Test
		@DisplayName("성공: kakao provider 로 정상 요청하면 200 과 accessToken 을 반환한다")
		void oauthLogin_success() throws Exception {
			OidcLoginRequestDto request = new OidcLoginRequestDto("kakao-id-token");
			given(oidcLoginService.login(AuthProvider.KAKAO, request.idToken()))
				.willReturn(new LoginResponseDto("jwt-token"));

			mockMvc.perform(post("/auth/oauth/kakao")
	@DisplayName("POST /auth/login")
	class Login {

		@Test
		@DisplayName("성공: 정상 요청이면 200 과 accessToken 을 반환한다")
		void login_success() throws Exception {
			LoginRequestDto request = new LoginRequestDto("test@example.com", "password123");
			given(authService.login(any(LoginRequestDto.class)))
				.willReturn(new LoginResponseDto("jwt-token"));

			mockMvc.perform(post(LOGIN_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.body.accessToken").value("jwt-token"));
		}

		@Test
		@DisplayName("실패: idToken 이 비어있으면 400 을 반환하고 서비스는 호출되지 않는다")
		void oauthLogin_blankIdToken() throws Exception {
			OidcLoginRequestDto request = new OidcLoginRequestDto("");

			mockMvc.perform(post("/auth/oauth/kakao")
		@DisplayName("실패: 이메일 형식이 잘못되면 400 을 반환하고 서비스는 호출되지 않는다")
		void login_invalidEmail() throws Exception {
			LoginRequestDto request = new LoginRequestDto("not-an-email", "password123");

			mockMvc.perform(post(LOGIN_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());

			verify(oidcLoginService, never()).login(any(), any());
		}

		@Test
		@DisplayName("실패: 지원하지 않는 provider 면 400 과 UNSUPPORTED_PROVIDER 코드를 반환하고 서비스는 호출되지 않는다")
		void oauthLogin_unsupportedProvider() throws Exception {
			OidcLoginRequestDto request = new OidcLoginRequestDto("some-id-token");

			mockMvc.perform(post("/auth/oauth/naver")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2422));

			verify(oidcLoginService, never()).login(any(), any());
		}

		@Test
		@DisplayName("실패: ID Token 검증에 실패하면 401 과 INVALID_ID_TOKEN 코드를 반환한다")
		void oauthLogin_invalidIdToken() throws Exception {
			OidcLoginRequestDto request = new OidcLoginRequestDto("bad-id-token");
			given(oidcLoginService.login(AuthProvider.KAKAO, request.idToken()))
				.willThrow(new ApiException(AuthErrorCode.INVALID_ID_TOKEN));

			mockMvc.perform(post("/auth/oauth/kakao")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2421));
			verify(authService, never()).login(any());
		}

		@Test
		@DisplayName("실패: 자격 증명이 틀리면 401 INVALID_CREDENTIALS(2411) 로 응답한다")
		void login_invalidCredentials() throws Exception {
			LoginRequestDto request = new LoginRequestDto("test@example.com", "wrong-password");
			given(authService.login(any(LoginRequestDto.class)))
				.willThrow(new ApiException(AuthErrorCode.INVALID_CREDENTIALS));

			mockMvc.perform(post(LOGIN_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2411))
				.andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다"));
		}
	}

	@Nested
	@DisplayName("POST /auth/logout")
	class Logout {

		@Test
		@DisplayName("성공: Bearer 토큰을 제거 대상으로 넘기고 200 을 반환한다")
		void logout_success() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer jwt-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			verify(authService).logout("jwt-token");
		}

		@Test
		@DisplayName("실패: Bearer 형식이 아니면 INVALID_TOKEN(2401) 을 반환하고 서비스는 호출되지 않는다")
		void logout_invalidAuthorizationHeader() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Basic jwt-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2401))
				.andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다"));

			verify(authService, never()).logout(any());
		}
	}
}
