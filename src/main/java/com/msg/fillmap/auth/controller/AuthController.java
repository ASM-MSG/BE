package com.msg.fillmap.auth.controller;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.dto.KakaoCodeLoginRequestDto;
import com.msg.fillmap.auth.dto.KakaoNonceResponseDto;
import com.msg.fillmap.auth.dto.LoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.LogoutRequestDto;
import com.msg.fillmap.auth.dto.OidcLoginRequestDto;
import com.msg.fillmap.auth.dto.ReissueRequestDto;
import com.msg.fillmap.auth.dto.ReissueResponseDto;
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
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.user.entity.AuthProvider;

@Tag(name = "인증 (Auth)", description = "회원가입·로그인·소셜 로그인·토큰 재발급 API. 이 그룹의 엔드포인트는 인증 없이 호출한다.")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String CLIENT_TYPE_HEADER = "X-Client-Type";
	private static final String DEVICE_ID_HEADER = "X-Device-Id";
	private static final String CLIENT_TYPE_APP = "app";
	private static final String CLIENT_TYPE_WEB = "web";

	private final AuthService authService;
	private final OidcLoginService oidcLoginService;
	private final RefreshTokenService refreshTokenService;
	private final KakaoAuthCodeExchanger kakaoAuthCodeExchanger;
	private final JwtProperties jwtProperties;
	private final KakaoOidcProperties kakaoOidcProperties;

	@Operation(summary = "이메일 회원가입", description = "이메일/비밀번호/닉네임으로 신규 회원을 생성한다.")
	@PostMapping("/signup")
	public SuccessResponse<SignupResponseDto> signup(@Valid @RequestBody SignupRequestDto request) {
		return SuccessResponse.of(authService.signup(request));
	}

	@Operation(
		summary = "이메일 로그인",
		description = "이메일/비밀번호로 로그인하고 JWT 액세스 토큰과 리프레시 토큰을 발급받는다. "
			+ "웹(X-Client-Type: web, 기본)은 리프레시가 HttpOnly 쿠키로, 앱(app)은 body 로 내려간다."
	)
	@PostMapping("/login")
	public SuccessResponse<LoginResponseDto> login(
		@Valid @RequestBody LoginRequestDto request,
		@Parameter(description = "클라이언트 유형 (web|app, 기본 web)")
		@RequestHeader(value = CLIENT_TYPE_HEADER, defaultValue = CLIENT_TYPE_WEB) String clientType,
		@Parameter(description = "디바이스 식별자. 없으면 서버가 UUID 를 생성해 응답 헤더 X-Device-Id 로 반환한다.")
		@RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceIdHeader,
		HttpServletResponse response
	) {
		String deviceId = resolveDeviceId(deviceIdHeader);
		LoginResponseDto issued = authService.login(request, deviceId);
		response.setHeader(DEVICE_ID_HEADER, deviceId);
		return SuccessResponse.of(applyTransport(issued, clientType, response));
	}

	@Operation(
		summary = "소셜 로그인 (OIDC)",
		description = "소셜 제공자의 ID Token으로 로그인/가입하고 JWT 액세스 토큰과 리프레시 토큰을 발급받는다."
	)
	@PostMapping("/oauth/{provider}")
	public SuccessResponse<LoginResponseDto> oauthLogin(
		@Parameter(description = "소셜 제공자", example = "KAKAO") @PathVariable String provider,
		@Valid @RequestBody OidcLoginRequestDto request,
		@Parameter(description = "클라이언트 유형 (web|app, 기본 web)")
		@RequestHeader(value = CLIENT_TYPE_HEADER, defaultValue = CLIENT_TYPE_WEB) String clientType,
		@Parameter(description = "디바이스 식별자. 없으면 서버가 UUID 를 생성해 응답 헤더 X-Device-Id 로 반환한다.")
		@RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceIdHeader,
		HttpServletResponse response
	) {
		AuthProvider authProvider = parseProvider(provider);
		String deviceId = resolveDeviceId(deviceIdHeader);
		LoginResponseDto issued = oidcLoginService.login(authProvider, request.idToken(), deviceId);
		response.setHeader(DEVICE_ID_HEADER, deviceId);
		return SuccessResponse.of(applyTransport(issued, clientType, response));
	}

	@Operation(
		summary = "카카오 로그인 nonce 발급",
		description = "카카오 인가 요청 직전에 부른다. 서버가 만든 1회용 난수를 body 로 주면서 같은 값을 "
			+ "HttpOnly 쿠키(OAUTH_NONCE, 10분)로도 심는다. 클라이언트는 받은 값을 인가 요청의 nonce 로 넣기만 "
			+ "하면 되고, 로그인 요청에는 다시 보내지 않는다(쿠키가 자동 동반). 서버는 돌아온 ID Token 의 nonce "
			+ "클레임을 이 쿠키와 대조해 다른 로그인 시도에서 만들어진 토큰을 거절한다."
	)
	@PostMapping("/oauth/kakao/nonce")
	public SuccessResponse<KakaoNonceResponseDto> issueOauthNonce(HttpServletResponse response) {
		String nonce = UUID.randomUUID().toString();
		response.addHeader(HttpHeaders.SET_COOKIE,
			NonceCookies.issue(nonce, kakaoOidcProperties.nonceCookieSecure()));
		return SuccessResponse.of(new KakaoNonceResponseDto(nonce));
	}

	@Operation(
		summary = "소셜 로그인 (카카오 인가 코드)",
		description = "웹에서 카카오 콜백으로 받은 인가 코드로 로그인/가입한다. 서버가 REST API 키로 카카오 토큰 "
			+ "엔드포인트를 호출해 ID Token 을 받은 뒤, 소셜 로그인(OIDC)과 완전히 같은 검증·발급 경로를 태운다. "
			+ "발급 API 가 심은 OAUTH_NONCE 쿠키가 함께 와야 한다(없으면 401). 응답 형태와 토큰 전송 "
			+ "규칙(웹=쿠키, 앱=body)은 기존 소셜 로그인과 동일하다. 네이티브 SDK 가 교환까지 해주는 앱은 "
			+ "이 API 가 아니라 POST /api/auth/oauth/{provider} 를 쓴다."
	)
	@PostMapping("/oauth/kakao/code")
	public SuccessResponse<LoginResponseDto> oauthCodeLogin(
		@Valid @RequestBody KakaoCodeLoginRequestDto request,
		@CookieValue(value = NonceCookies.COOKIE_NAME, required = false) String nonceCookie,
		@Parameter(description = "클라이언트 유형 (web|app, 기본 web)")
		@RequestHeader(value = CLIENT_TYPE_HEADER, defaultValue = CLIENT_TYPE_WEB) String clientType,
		@Parameter(description = "디바이스 식별자. 없으면 서버가 UUID 를 생성해 응답 헤더 X-Device-Id 로 반환한다.")
		@RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceIdHeader,
		HttpServletResponse response
	) {
		if (nonceCookie == null || nonceCookie.isBlank()) {
			// 발급 API 를 안 거친 요청 — 대조할 기대값이 없으면 방어가 없는 것과 같으므로 카카오 왕복 전에 끊는다
			throw new ApiException(AuthErrorCode.INVALID_AUTHORIZATION_CODE);
		}
		String deviceId = resolveDeviceId(deviceIdHeader);
		// 카카오 왕복은 OidcLoginService 의 트랜잭션 경계 밖에서 끝낸다 — 외부 API 지연이 DB 커넥션 점유로 번지지 않는다
		String idToken = kakaoAuthCodeExchanger.exchange(request.code(), request.redirectUri(), nonceCookie);
		LoginResponseDto issued = oidcLoginService.login(AuthProvider.KAKAO, idToken, deviceId);
		response.setHeader(DEVICE_ID_HEADER, deviceId);
		// 쓴 nonce 는 즉시 만료 — 재사용 창을 로그인 1회로 좁힌다
		response.addHeader(HttpHeaders.SET_COOKIE, NonceCookies.expire(kakaoOidcProperties.nonceCookieSecure()));
		return SuccessResponse.of(applyTransport(issued, clientType, response));
	}

	@Operation(
		summary = "토큰 재발급",
		description = "리프레시 토큰(웹=쿠키, 앱=body)으로 새 액세스 토큰과 회전된 새 리프레시 토큰을 발급받는다. "
			+ "직전 리프레시 토큰은 즉시 무효화되며, 회전된 옛 토큰 재사용 시 세션 체인이 폐기된다."
	)
	@PostMapping("/reissue")
	public SuccessResponse<ReissueResponseDto> reissue(
		@CookieValue(value = RefreshTokenCookies.COOKIE_NAME, required = false) String cookieRefreshToken,
		@RequestBody(required = false) ReissueRequestDto request,
		@Parameter(description = "클라이언트 유형 (web|app, 기본 web)")
		@RequestHeader(value = CLIENT_TYPE_HEADER, defaultValue = CLIENT_TYPE_WEB) String clientType,
		HttpServletResponse response
	) {
		String refreshToken = resolveRefreshToken(cookieRefreshToken, request);
		ReissueResult result = refreshTokenService.reissue(refreshToken);
		response.setHeader(DEVICE_ID_HEADER, result.deviceId());
		if (isApp(clientType)) {
			return SuccessResponse.of(new ReissueResponseDto(result.accessToken(), result.refreshToken()));
		}
		response.addHeader(HttpHeaders.SET_COOKIE,
			RefreshTokenCookies.issue(result.refreshToken(), jwtProperties.refreshTokenTtl()));
		return SuccessResponse.of(new ReissueResponseDto(result.accessToken(), null));
	}

	@Operation(
		summary = "로그아웃",
		description = "Authorization 헤더의 액세스 토큰을 무효화하고 해당 디바이스(X-Device-Id)의 리프레시 세션을 삭제한다. "
			+ "X-Device-Id 가 없으면 해당 유저의 모든 디바이스 세션을 삭제한다. "
			+ "선택 body 의 fcmToken 이 있으면 해당 FCM 푸시 토큰도 함께 정리된다 (MSG-178 logout 통합)."
	)
	@PostMapping("/logout")
	public SuccessResponse<Void> logout(
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		@Parameter(description = "디바이스 식별자. 없으면 모든 디바이스 세션 삭제(로그아웃-올).")
		@RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceId,
		@RequestBody(required = false) LogoutRequestDto request,
		HttpServletResponse response
	) {
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			throw new ApiException(AuthErrorCode.INVALID_TOKEN);
		}
		String fcmToken = request != null ? request.fcmToken() : null;
		authService.logout(authorization.substring(BEARER_PREFIX.length()), deviceId, fcmToken);
		response.addHeader(HttpHeaders.SET_COOKIE, RefreshTokenCookies.expire());
		return new SuccessResponse<>(null);
	}

	private AuthProvider parseProvider(String provider) {
		try {
			return AuthProvider.valueOf(provider.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ApiException(AuthErrorCode.UNSUPPORTED_PROVIDER);
		}
	}

	private String resolveDeviceId(String deviceIdHeader) {
		if (deviceIdHeader == null || deviceIdHeader.isBlank()) {
			return UUID.randomUUID().toString();
		}
		return deviceIdHeader;
	}

	private String resolveRefreshToken(String cookieRefreshToken, ReissueRequestDto request) {
		// 쿠키에 있으면 쿠키에서, 없으면 body 에서 읽는다 (MSG-135 API 명세 3)
		if (cookieRefreshToken != null && !cookieRefreshToken.isBlank()) {
			return cookieRefreshToken;
		}
		if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
			return request.refreshToken();
		}
		throw new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN);
	}

	private LoginResponseDto applyTransport(LoginResponseDto issued, String clientType, HttpServletResponse response) {
		if (isApp(clientType)) {
			return issued;
		}
		response.addHeader(HttpHeaders.SET_COOKIE,
			RefreshTokenCookies.issue(issued.refreshToken(), jwtProperties.refreshTokenTtl()));
		return new LoginResponseDto(issued.accessToken(), null);
	}

	private boolean isApp(String clientType) {
		return CLIENT_TYPE_APP.equalsIgnoreCase(clientType);
	}
}
