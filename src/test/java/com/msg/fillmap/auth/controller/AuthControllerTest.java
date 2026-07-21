package com.msg.fillmap.auth.controller;

import static org.hamcrest.Matchers.containsString;
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

import java.time.Duration;
import java.time.LocalDateTime;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.auth.dto.LoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.OidcLoginRequestDto;
import com.msg.fillmap.auth.dto.ReissueRequestDto;
import com.msg.fillmap.auth.dto.SignupRequestDto;
import com.msg.fillmap.auth.dto.SignupResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.service.AuthService;
import com.msg.fillmap.auth.service.OidcLoginService;
import com.msg.fillmap.auth.service.RefreshTokenService;
import com.msg.fillmap.auth.service.ReissueResult;
import com.msg.fillmap.auth.support.RefreshTokenCookies;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.exception.UserErrorCode;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthControllerTest.JwtPropertiesTestConfig.class)
@DisplayName("AuthController")
class AuthControllerTest {

	private static final String SIGNUP_URL = "/api/auth/signup";
	private static final String LOGIN_URL = "/api/auth/login";
	private static final String OAUTH_URL = "/api/auth/oauth/kakao";
	private static final String REISSUE_URL = "/api/auth/reissue";
	private static final String LOGOUT_URL = "/api/auth/logout";
	private static final String CLIENT_TYPE_HEADER = "X-Client-Type";
	private static final String DEVICE_ID_HEADER = "X-Device-Id";

	@TestConfiguration
	static class JwtPropertiesTestConfig {

		@Bean
		JwtProperties jwtProperties() {
			return new JwtProperties(
				"test-access-secret", Duration.ofHours(1), "test-refresh-secret", Duration.ofDays(14));
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private OidcLoginService oidcLoginService;

	@MockitoBean
	private RefreshTokenService refreshTokenService;

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
	@DisplayName("POST /auth/login")
	class Login {

		@Test
		@DisplayName("성공(웹): 리프레시를 Set-Cookie 로 내리고 body.refreshToken 은 null 이며 X-Device-Id 를 반환한다")
		void login_web_setsCookie() throws Exception {
			LoginRequestDto request = new LoginRequestDto("test@example.com", "password123");
			given(authService.login(any(LoginRequestDto.class), anyString()))
				.willReturn(new LoginResponseDto("access-jwt", "refresh-jwt"));

			mockMvc.perform(post(LOGIN_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.body.accessToken").value("access-jwt"))
				.andExpect(jsonPath("$.body.refreshToken").isEmpty())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=")))
				.andExpect(header().exists(DEVICE_ID_HEADER));
		}

		@Test
		@DisplayName("성공(앱): 리프레시를 body 로 내리고 쿠키는 없다")
		void login_app_returnsBody() throws Exception {
			LoginRequestDto request = new LoginRequestDto("test@example.com", "password123");
			given(authService.login(any(LoginRequestDto.class), anyString()))
				.willReturn(new LoginResponseDto("access-jwt", "refresh-jwt"));

			mockMvc.perform(post(LOGIN_URL)
					.header(CLIENT_TYPE_HEADER, "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.body.accessToken").value("access-jwt"))
				.andExpect(jsonPath("$.body.refreshToken").value("refresh-jwt"))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		}

		@Test
		@DisplayName("클라이언트가 보낸 X-Device-Id 를 그대로 응답 헤더로 에코한다")
		void login_echoesDeviceId() throws Exception {
			LoginRequestDto request = new LoginRequestDto("test@example.com", "password123");
			given(authService.login(any(LoginRequestDto.class), anyString()))
				.willReturn(new LoginResponseDto("access-jwt", "refresh-jwt"));

			mockMvc.perform(post(LOGIN_URL)
					.header(DEVICE_ID_HEADER, "device-abc")
					.header(CLIENT_TYPE_HEADER, "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(header().string(DEVICE_ID_HEADER, "device-abc"));

			verify(authService).login(any(LoginRequestDto.class), eq("device-abc"));
		}

		@Test
		@DisplayName("실패: 이메일 형식이 잘못되면 400 을 반환하고 서비스는 호출되지 않는다")
		void login_invalidEmail() throws Exception {
			LoginRequestDto request = new LoginRequestDto("not-an-email", "password123");

			mockMvc.perform(post(LOGIN_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());

			verify(authService, never()).login(any(), any());
		}

		@Test
		@DisplayName("실패: 자격 증명이 틀리면 401 INVALID_CREDENTIALS(2411) 로 응답한다")
		void login_invalidCredentials() throws Exception {
			LoginRequestDto request = new LoginRequestDto("test@example.com", "wrong-password");
			given(authService.login(any(LoginRequestDto.class), anyString()))
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
	@DisplayName("POST /auth/oauth/{provider}")
	class OauthLogin {

		@Test
		@DisplayName("성공: kakao provider 로 정상 요청하면 200 과 accessToken 을 반환한다")
		void oauthLogin_success() throws Exception {
			OidcLoginRequestDto request = new OidcLoginRequestDto("kakao-id-token");
			given(oidcLoginService.login(eq(AuthProvider.KAKAO), eq("kakao-id-token"), anyString()))
				.willReturn(new LoginResponseDto("jwt-token", "refresh-jwt"));

			mockMvc.perform(post(OAUTH_URL)
					.header(CLIENT_TYPE_HEADER, "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.body.accessToken").value("jwt-token"))
				.andExpect(jsonPath("$.body.refreshToken").value("refresh-jwt"));
		}

		@Test
		@DisplayName("실패: idToken 이 비어있으면 400 을 반환하고 서비스는 호출되지 않는다")
		void oauthLogin_blankIdToken() throws Exception {
			OidcLoginRequestDto request = new OidcLoginRequestDto("");

			mockMvc.perform(post(OAUTH_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());

			verify(oidcLoginService, never()).login(any(), any(), any());
		}

		@Test
		@DisplayName("실패: 지원하지 않는 provider 면 400 과 UNSUPPORTED_PROVIDER 코드를 반환하고 서비스는 호출되지 않는다")
		void oauthLogin_unsupportedProvider() throws Exception {
			OidcLoginRequestDto request = new OidcLoginRequestDto("some-id-token");

			mockMvc.perform(post("/api/auth/oauth/naver")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2422));

			verify(oidcLoginService, never()).login(any(), any(), any());
		}

		@Test
		@DisplayName("실패: ID Token 검증에 실패하면 401 과 INVALID_ID_TOKEN 코드를 반환한다")
		void oauthLogin_invalidIdToken() throws Exception {
			OidcLoginRequestDto request = new OidcLoginRequestDto("bad-id-token");
			given(oidcLoginService.login(eq(AuthProvider.KAKAO), eq("bad-id-token"), anyString()))
				.willThrow(new ApiException(AuthErrorCode.INVALID_ID_TOKEN));

			mockMvc.perform(post(OAUTH_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2421));
		}
	}

	@Nested
	@DisplayName("POST /auth/reissue")
	class Reissue {

		@Test
		@DisplayName("쿠키에 리프레시가 있으면 쿠키에서 읽어 재발급하고, 웹은 새 리프레시를 Set-Cookie 로 내린다")
		void reissue_readsFromCookie() throws Exception {
			given(refreshTokenService.reissue("refresh-cookie"))
				.willReturn(new ReissueResult("new-access", "new-refresh", "device-1"));

			mockMvc.perform(post(REISSUE_URL)
					.cookie(new Cookie(RefreshTokenCookies.COOKIE_NAME, "refresh-cookie")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.body.accessToken").value("new-access"))
				.andExpect(jsonPath("$.body.refreshToken").isEmpty())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=")))
				.andExpect(header().string(DEVICE_ID_HEADER, "device-1"));

			verify(refreshTokenService).reissue("refresh-cookie");
		}

		@Test
		@DisplayName("쿠키가 없으면 body 에서 읽어 재발급하고, 앱은 새 리프레시를 body 로 내린다")
		void reissue_readsFromBody() throws Exception {
			ReissueRequestDto request = new ReissueRequestDto("refresh-body");
			given(refreshTokenService.reissue("refresh-body"))
				.willReturn(new ReissueResult("new-access", "new-refresh", "device-2"));

			mockMvc.perform(post(REISSUE_URL)
					.header(CLIENT_TYPE_HEADER, "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.body.accessToken").value("new-access"))
				.andExpect(jsonPath("$.body.refreshToken").value("new-refresh"))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

			verify(refreshTokenService).reissue("refresh-body");
		}

		@Test
		@DisplayName("실패: 쿠키·body 모두 리프레시가 없으면 401 INVALID_REFRESH_TOKEN(2431) 을 반환한다")
		void reissue_noToken() throws Exception {
			mockMvc.perform(post(REISSUE_URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2431));

			verify(refreshTokenService, never()).reissue(any());
		}

		@Test
		@DisplayName("실패: 재사용이 감지되면 401 REFRESH_TOKEN_REUSE_DETECTED(2433) 을 반환한다")
		void reissue_reuseDetected() throws Exception {
			given(refreshTokenService.reissue("reused-refresh"))
				.willThrow(new ApiException(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED));

			mockMvc.perform(post(REISSUE_URL)
					.cookie(new Cookie(RefreshTokenCookies.COOKIE_NAME, "reused-refresh")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2433));
		}

		@Test
		@DisplayName("실패: 만료·미존재 리프레시면 401 EXPIRED_REFRESH_TOKEN(2432) 을 반환한다")
		void reissue_expired() throws Exception {
			given(refreshTokenService.reissue("expired-refresh"))
				.willThrow(new ApiException(AuthErrorCode.EXPIRED_REFRESH_TOKEN));

			mockMvc.perform(post(REISSUE_URL)
					.cookie(new Cookie(RefreshTokenCookies.COOKIE_NAME, "expired-refresh")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2432));
		}
	}

	@Nested
	@DisplayName("POST /auth/logout")
	class Logout {

		@Test
		@DisplayName("성공: 액세스 토큰과 X-Device-Id 를 넘기고 리프레시 쿠키를 만료시키며 200 을 반환한다")
		void logout_success() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer jwt-token")
					.header(DEVICE_ID_HEADER, "device-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

			verify(authService).logout("jwt-token", "device-1");
		}

		@Test
		@DisplayName("X-Device-Id 가 없으면 deviceId=null 로 로그아웃-올을 위임한다")
		void logout_withoutDeviceId() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer jwt-token"))
				.andExpect(status().isOk());

			verify(authService).logout("jwt-token", null);
		}

		@Test
		@DisplayName("실패: Bearer 형식이 아니면 INVALID_TOKEN(2401) 을 반환하고 서비스는 호출되지 않는다")
		void logout_invalidAuthorizationHeader() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Basic jwt-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2401))
				.andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다"));

			verify(authService, never()).logout(any(), any());
		}

		@Test
		@DisplayName("실패: Authorization 헤더가 없으면 INVALID_TOKEN(2401) 을 반환하고 서비스는 호출되지 않는다")
		void logout_missingAuthorizationHeader() throws Exception {
			mockMvc.perform(post(LOGOUT_URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2401))
				.andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다"));

			verify(authService, never()).logout(any(), any());
		}
	}
}
