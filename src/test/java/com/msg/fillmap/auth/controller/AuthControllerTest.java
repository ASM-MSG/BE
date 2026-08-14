package com.msg.fillmap.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.auth.dto.KakaoCodeLoginRequestDto;
import com.msg.fillmap.auth.dto.LoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.OidcLoginRequestDto;
import com.msg.fillmap.auth.dto.ReissueRequestDto;
import com.msg.fillmap.auth.dto.SignupRequestDto;
import com.msg.fillmap.auth.dto.SignupResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.oidc.KakaoAuthCodeExchanger;
import com.msg.fillmap.auth.oidc.KakaoOidcProperties;
import com.msg.fillmap.auth.service.AuthService;
import com.msg.fillmap.auth.service.OidcLoginService;
import com.msg.fillmap.auth.service.RefreshTokenService;
import com.msg.fillmap.auth.service.ReissueResult;
import com.msg.fillmap.auth.support.NonceCookies;
import com.msg.fillmap.auth.support.RefreshTokenCookies;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.exception.UserErrorCode;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthControllerTest.PropertiesTestConfig.class)
@DisplayName("AuthController")
class AuthControllerTest {

	private static final String SIGNUP_URL = "/api/auth/signup";
	private static final String LOGIN_URL = "/api/auth/login";
	private static final String OAUTH_URL = "/api/auth/oauth/kakao";
	private static final String OAUTH_CODE_URL = "/api/auth/oauth/kakao/code";
	private static final String OAUTH_AUTHORIZE_URL = "/api/auth/oauth/kakao/authorize";
	private static final String REISSUE_URL = "/api/auth/reissue";
	private static final String LOGOUT_URL = "/api/auth/logout";
	private static final String CLIENT_TYPE_HEADER = "X-Client-Type";
	private static final String DEVICE_ID_HEADER = "X-Device-Id";

	/** @WebMvcTest 는 @ConfigurationProperties 를 바인딩하지 않으므로 컨트롤러가 쓰는 설정을 직접 넣는다. */
	@TestConfiguration
	static class PropertiesTestConfig {

		@Bean
		JwtProperties jwtProperties() {
			return new JwtProperties(
				"test-access-secret", Duration.ofHours(1), "test-refresh-secret", Duration.ofDays(14));
		}

		@Bean
		KakaoOidcProperties kakaoOidcProperties() {
			// nonceCookieSecure=true — 공통(dev·prod) 기본값. false 분기는 NonceCookies 직접 단언으로 덮는다
			return new KakaoOidcProperties("https://kauth.kakao.com",
				"https://kauth.kakao.com/.well-known/jwks.json", "test-client-id",
				"https://kauth.kakao.com/oauth/token", "https://kauth.kakao.com/oauth/authorize", true);
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

	@MockitoBean
	private KakaoAuthCodeExchanger kakaoAuthCodeExchanger;

	@Nested
	@DisplayName("POST /auth/signup")
	class Signup {

		// 검증: FR-AUTH-11
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
				.andExpect(jsonPath("$.data.id").value(1))
				.andExpect(jsonPath("$.data.email").value("test@example.com"))
				.andExpect(jsonPath("$.data.nickname").value("테스터"));
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

		// 검증: FR-AUTH-11
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

		// 검증: FR-AUTH-11
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

		// 검증: FR-USER-04
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

		// 검증: FR-AUTH-08
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
				.andExpect(jsonPath("$.data.accessToken").value("access-jwt"))
				.andExpect(jsonPath("$.data.refreshToken").isEmpty())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=")))
				.andExpect(header().exists(DEVICE_ID_HEADER));
		}

		// 검증: FR-AUTH-08
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
				.andExpect(jsonPath("$.data.accessToken").value("access-jwt"))
				.andExpect(jsonPath("$.data.refreshToken").value("refresh-jwt"))
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

		// 검증: FR-AUTH-01
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
				.andExpect(jsonPath("$.data.accessToken").value("jwt-token"))
				.andExpect(jsonPath("$.data.refreshToken").value("refresh-jwt"));
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

		// 검증: FR-AUTH-01
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
	@DisplayName("/auth/oauth/kakao/* — 웹 인가 진입점과 코드 교환 로그인 (MSG-345)")
	class OauthCodeLogin {

		private static final String CODE = "kakao-auth-code";
		private static final String REDIRECT_URI = "http://localhost:5173/oauth/kakao/callback";
		private static final String NONCE = "f47ac10b58cc4372a5670e02b2c3d479";

		private KakaoCodeLoginRequestDto request() {
			return new KakaoCodeLoginRequestDto(CODE, REDIRECT_URI);
		}

		/** 발급 API 가 심어 브라우저가 자동 동반하는 쿠키. 기대 nonce 는 요청 body 가 아니라 여기서 온다. */
		private Cookie nonceCookie() {
			return new Cookie(NonceCookies.COOKIE_NAME, NONCE);
		}

		// 검증: FR-AUTH-02, FR-AUTH-03
		@Test
		@DisplayName("인가 진입점: 카카오 인가 URL 로 302 하면서 같은 응답에 nonce 쿠키를 심는다")
		void 인가_진입점은_카카오_인가_URL로_302하며_nonce_쿠키를_심는다() throws Exception {
			MvcResult result = mockMvc.perform(get(OAUTH_AUTHORIZE_URL).param("redirectUri", REDIRECT_URI))
				.andExpect(status().isFound())
				.andExpect(cookie().httpOnly(NonceCookies.COOKIE_NAME, true))
				.andExpect(cookie().maxAge(NonceCookies.COOKIE_NAME, 600))
				// 설정값(nonce-cookie-secure, 여기선 공통 기본 true)이 그대로 쿠키 속성에 실린다
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=None")))
				.andReturn();

			String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
			// 쿠키에 심은 값과 인가 URL 의 nonce 가 같아야 대조가 성립한다 — 이 한 쌍이 결속의 전부다
			String cookieNonce = result.getResponse().getCookie(NonceCookies.COOKIE_NAME).getValue();
			assertThat(location)
				.startsWith("https://kauth.kakao.com/oauth/authorize?")
				.contains("client_id=test-client-id")
				.contains("redirect_uri=" + REDIRECT_URI)
				.contains("response_type=code")
				.contains("scope=openid")
				.contains("nonce=" + cookieNonce)
				.doesNotContain("state=");
		}

		// 검증: FR-AUTH-02
		@Test
		@DisplayName("인가 진입점: state 는 손대지 않고 인가 URL 에 그대로 전달한다")
		void 인가_진입점은_state를_그대로_인가_URL에_전달한다() throws Exception {
			mockMvc.perform(get(OAUTH_AUTHORIZE_URL)
					.param("redirectUri", REDIRECT_URI)
					.param("state", "fe-state-123"))
				.andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION, containsString("state=fe-state-123")));
		}

		@Test
		@DisplayName("실패: 인가 진입점에 redirectUri 가 없으면 400 이다")
		void 인가_진입점에_redirectUri가_없으면_400이다() throws Exception {
			// 명세엔 필수로 노출하되(@Parameter required = true) 누락 응답은 우리가 정한 메시지로 낸다 (MSG-332 선례)
			mockMvc.perform(get(OAUTH_AUTHORIZE_URL))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400))
				.andExpect(jsonPath("$.message").value("redirectUri는 필수 항목입니다"));
		}

		@Test
		@DisplayName("nonce 쿠키 속성: 설정이 꺼진 로컬은 Secure 없이 SameSite=Lax 로 내려간다")
		void 논스_쿠키_속성은_secure_설정에_따라_갈린다() {
			// http://localhost 는 Secure 쿠키를 저장하지 않는다 — 배포 속성을 그대로 쓰면 로컬 웹 로그인이 전부 2423
			assertThat(NonceCookies.issue("test-nonce", false)).contains("SameSite=Lax").doesNotContain("Secure");
			assertThat(NonceCookies.issue("test-nonce", true)).contains("SameSite=None", "Secure");
		}

		// 검증: FR-AUTH-02, FR-AUTH-08
		@Test
		@DisplayName("성공(웹): 교환한 ID Token 으로 로그인해 액세스 토큰과 리프레시 쿠키를 내린다")
		void 유효한_인가_코드로_로그인하면_웹은_액세스_토큰과_리프레시_쿠키가_발급된다() throws Exception {
			given(kakaoAuthCodeExchanger.exchange(CODE, REDIRECT_URI, NONCE)).willReturn("kakao-id-token");
			given(oidcLoginService.login(eq(AuthProvider.KAKAO), eq("kakao-id-token"), anyString()))
				.willReturn(new LoginResponseDto("access-jwt", "refresh-jwt"));

			mockMvc.perform(post(OAUTH_CODE_URL)
					.cookie(nonceCookie())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.accessToken").value("access-jwt"))
				.andExpect(jsonPath("$.data.refreshToken").isEmpty())
				.andExpect(cookie().exists(RefreshTokenCookies.COOKIE_NAME))
				.andExpect(header().exists(DEVICE_ID_HEADER));
		}

		// 검증: FR-AUTH-02, FR-AUTH-08
		@Test
		@DisplayName("성공(앱): 리프레시를 body 로 내리고 리프레시 쿠키는 없다 — 기존 소셜 로그인과 같은 전송 규칙")
		void 앱_클라이언트는_리프레시_토큰이_body로_내려간다() throws Exception {
			given(kakaoAuthCodeExchanger.exchange(CODE, REDIRECT_URI, NONCE)).willReturn("kakao-id-token");
			given(oidcLoginService.login(eq(AuthProvider.KAKAO), eq("kakao-id-token"), anyString()))
				.willReturn(new LoginResponseDto("access-jwt", "refresh-jwt"));

			mockMvc.perform(post(OAUTH_CODE_URL)
					.cookie(nonceCookie())
					.header(CLIENT_TYPE_HEADER, "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accessToken").value("access-jwt"))
				.andExpect(jsonPath("$.data.refreshToken").value("refresh-jwt"))
				.andExpect(cookie().doesNotExist(RefreshTokenCookies.COOKIE_NAME));
		}

		// 검증: FR-AUTH-08
		@Test
		@DisplayName("X-Device-Id 가 없으면 서버가 생성해 서비스로 넘기고 응답 헤더로 반환한다")
		void 디바이스_ID_헤더가_없으면_서버가_생성해_응답_헤더로_반환한다() throws Exception {
			given(kakaoAuthCodeExchanger.exchange(CODE, REDIRECT_URI, NONCE)).willReturn("kakao-id-token");
			given(oidcLoginService.login(eq(AuthProvider.KAKAO), eq("kakao-id-token"), anyString()))
				.willReturn(new LoginResponseDto("access-jwt", "refresh-jwt"));

			mockMvc.perform(post(OAUTH_CODE_URL)
					.cookie(nonceCookie())
					.header(CLIENT_TYPE_HEADER, "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request())))
				.andExpect(status().isOk())
				.andExpect(header().exists(DEVICE_ID_HEADER));

			verify(oidcLoginService).login(eq(AuthProvider.KAKAO), eq("kakao-id-token"), anyString());
		}

		@Test
		@DisplayName("로그인에 성공하면 쓴 nonce 쿠키를 즉시 만료시킨다 — 재사용 창을 1회로 좁힌다")
		void 로그인_성공_시_논스_쿠키가_만료된다() throws Exception {
			given(kakaoAuthCodeExchanger.exchange(CODE, REDIRECT_URI, NONCE)).willReturn("kakao-id-token");
			given(oidcLoginService.login(eq(AuthProvider.KAKAO), eq("kakao-id-token"), anyString()))
				.willReturn(new LoginResponseDto("access-jwt", "refresh-jwt"));

			mockMvc.perform(post(OAUTH_CODE_URL)
					.cookie(nonceCookie())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request())))
				.andExpect(status().isOk())
				.andExpect(cookie().maxAge(NonceCookies.COOKIE_NAME, 0))
				.andExpect(cookie().value(NonceCookies.COOKIE_NAME, ""));
		}

		// 검증: FR-AUTH-03
		@Test
		@DisplayName("실패: nonce 쿠키가 없으면 401(2423) — 카카오 왕복도 하지 않는다")
		void 논스_쿠키가_없으면_카카오_왕복_없이_2423으로_거절한다() throws Exception {
			mockMvc.perform(post(OAUTH_CODE_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2423))
				.andExpect(jsonPath("$.message").value("유효하지 않은 인가 코드입니다"));

			verify(kakaoAuthCodeExchanger, never()).exchange(any(), any(), any());
			verify(oidcLoginService, never()).login(any(), any(), any());
		}

		@Test
		@DisplayName("실패: code 가 비어있으면 400 을 반환하고 교환은 시도하지 않는다")
		void code가_없으면_400_검증_에러가_난다() throws Exception {
			mockMvc.perform(post(OAUTH_CODE_URL)
					.cookie(nonceCookie())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(new KakaoCodeLoginRequestDto("", REDIRECT_URI))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));

			verify(kakaoAuthCodeExchanger, never()).exchange(any(), any(), any());
			verify(oidcLoginService, never()).login(any(), any(), any());
		}

		@Test
		@DisplayName("실패: redirectUri 가 비어있으면 400 을 반환하고 교환은 시도하지 않는다")
		void redirectUri가_없으면_400_검증_에러가_난다() throws Exception {
			mockMvc.perform(post(OAUTH_CODE_URL)
					.cookie(nonceCookie())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(new KakaoCodeLoginRequestDto(CODE, ""))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));

			verify(kakaoAuthCodeExchanger, never()).exchange(any(), any(), any());
			verify(oidcLoginService, never()).login(any(), any(), any());
		}

		// 검증: FR-AUTH-10
		@Test
		@DisplayName("실패: 교환이 거부되면 401 INVALID_AUTHORIZATION_CODE(2423) 로 응답하고 로그인은 하지 않는다")
		void 교환_실패_예외는_2423_응답으로_변환된다() throws Exception {
			given(kakaoAuthCodeExchanger.exchange(CODE, REDIRECT_URI, NONCE))
				.willThrow(new ApiException(AuthErrorCode.INVALID_AUTHORIZATION_CODE));

			mockMvc.perform(post(OAUTH_CODE_URL)
					.cookie(nonceCookie())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2423))
				.andExpect(jsonPath("$.message").value("유효하지 않은 인가 코드입니다"));

			verify(oidcLoginService, never()).login(any(), any(), any());
		}

		@Test
		@DisplayName("실패: 교환한 ID Token 이 검증에 실패하면 기존 401 INVALID_ID_TOKEN(2421) 로 응답한다")
		void 교환으로_받은_ID_토큰이_검증에_실패하면_기존_2421로_응답한다() throws Exception {
			given(kakaoAuthCodeExchanger.exchange(CODE, REDIRECT_URI, NONCE)).willReturn("bad-id-token");
			given(oidcLoginService.login(eq(AuthProvider.KAKAO), eq("bad-id-token"), anyString()))
				.willThrow(new ApiException(AuthErrorCode.INVALID_ID_TOKEN));

			mockMvc.perform(post(OAUTH_CODE_URL)
					.cookie(nonceCookie())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2421));
		}
	}

	@Nested
	@DisplayName("POST /auth/reissue")
	class Reissue {

		// 검증: FR-AUTH-08
		@Test
		@DisplayName("쿠키에 리프레시가 있으면 쿠키에서 읽어 재발급하고, 웹은 새 리프레시를 Set-Cookie 로 내린다")
		void reissue_readsFromCookie() throws Exception {
			given(refreshTokenService.reissue("refresh-cookie"))
				.willReturn(new ReissueResult("new-access", "new-refresh", "device-1"));

			mockMvc.perform(post(REISSUE_URL)
					.header(CLIENT_TYPE_HEADER, "web")
					.cookie(new Cookie(RefreshTokenCookies.COOKIE_NAME, "refresh-cookie")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accessToken").value("new-access"))
				.andExpect(jsonPath("$.data.refreshToken").isEmpty())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=")))
				.andExpect(header().string(DEVICE_ID_HEADER, "device-1"));

			verify(refreshTokenService).reissue("refresh-cookie");
		}

		// 검증: FR-AUTH-08
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
				.andExpect(jsonPath("$.data.accessToken").value("new-access"))
				.andExpect(jsonPath("$.data.refreshToken").value("new-refresh"))
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

		// 검증: FR-AUTH-07
		@Test
		@DisplayName("실패: 재사용이 감지되면 401 REFRESH_TOKEN_REUSE_DETECTED(2433) 을 반환한다")
		void reissue_reuseDetected() throws Exception {
			given(refreshTokenService.reissue("reused-refresh"))
				.willThrow(new ApiException(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED));

			mockMvc.perform(post(REISSUE_URL)
					.header(CLIENT_TYPE_HEADER, "web")
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
					.header(CLIENT_TYPE_HEADER, "web")
					.cookie(new Cookie(RefreshTokenCookies.COOKIE_NAME, "expired-refresh")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2432));
		}

		@Test
		@DisplayName("실패: 쿠키로 리프레시가 왔는데 X-Client-Type 이 없으면 400 MISSING_CLIENT_TYPE_HEADER(2434) 를 반환한다")
		void reissue_cookieWithoutClientTypeHeader() throws Exception {
			// 커스텀 헤더가 없으면 크로스사이트 폼 POST 도 통과한다 — CSRF 방어의 전제인 preflight 가 안 걸린다 (MSG-135)
			mockMvc.perform(post(REISSUE_URL)
					.cookie(new Cookie(RefreshTokenCookies.COOKIE_NAME, "refresh-cookie")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2434));

			verify(refreshTokenService, never()).reissue(any());
		}

		@Test
		@DisplayName("body 로 리프레시를 보내는 경로는 X-Client-Type 없이도 재발급된다 (헤더 요구는 쿠키 경로 한정)")
		void reissue_bodyWithoutClientTypeHeader() throws Exception {
			ReissueRequestDto request = new ReissueRequestDto("refresh-body");
			given(refreshTokenService.reissue("refresh-body"))
				.willReturn(new ReissueResult("new-access", "new-refresh", "device-3"));

			mockMvc.perform(post(REISSUE_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accessToken").value("new-access"))
				// 헤더가 없으면 종전대로 web 취급 — 새 리프레시는 쿠키로 내려간다
				.andExpect(jsonPath("$.data.refreshToken").isEmpty())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=")));

			verify(refreshTokenService).reissue("refresh-body");
		}
	}

	@Nested
	@DisplayName("POST /auth/logout")
	class Logout {

		// 검증: FR-AUTH-09
		@Test
		@DisplayName("성공: 액세스 토큰과 X-Device-Id 를 넘기고 리프레시 쿠키를 만료시키며 200 을 반환한다")
		void logout_success() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer jwt-token")
					.header(DEVICE_ID_HEADER, "device-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

			verify(authService).logout("jwt-token", "device-1", null);
		}

		@Test
		@DisplayName("X-Device-Id 가 없으면 deviceId=null 로 로그아웃-올을 위임한다")
		void logout_withoutDeviceId() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer jwt-token"))
				.andExpect(status().isOk());

			verify(authService).logout("jwt-token", null, null);
		}

		// 검증: FR-NOTI-01
		@Test
		@DisplayName("body 에 fcmToken 이 있으면 서비스로 전달돼 푸시 토큰도 정리된다 (MSG-178 logout 통합)")
		void 로그아웃_body에_fcmToken이_있으면_푸시_토큰도_정리된다() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer jwt-token")
					.header(DEVICE_ID_HEADER, "device-1")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"fcmToken\":\"fcm-token-abc\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			verify(authService).logout("jwt-token", "device-1", "fcm-token-abc");
		}

		@Test
		@DisplayName("body 없는 기존 호출은 그대로 동작한다 — 하위 호환 (MSG-178)")
		void 로그아웃_body_없는_기존_호출은_그대로_동작한다() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer jwt-token")
					.header(DEVICE_ID_HEADER, "device-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			verify(authService).logout("jwt-token", "device-1", null);
		}

		@Test
		@DisplayName("실패: Bearer 형식이 아니면 INVALID_TOKEN(2401) 을 반환하고 서비스는 호출되지 않는다")
		void logout_invalidAuthorizationHeader() throws Exception {
			mockMvc.perform(post(LOGOUT_URL)
					.header(HttpHeaders.AUTHORIZATION, "Basic jwt-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2401))
				.andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다"));

			verify(authService, never()).logout(any(), any(), any());
		}

		@Test
		@DisplayName("실패: Authorization 헤더가 없으면 INVALID_TOKEN(2401) 을 반환하고 서비스는 호출되지 않는다")
		void logout_missingAuthorizationHeader() throws Exception {
			mockMvc.perform(post(LOGOUT_URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2401))
				.andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다"));

			verify(authService, never()).logout(any(), any(), any());
		}
	}
}
