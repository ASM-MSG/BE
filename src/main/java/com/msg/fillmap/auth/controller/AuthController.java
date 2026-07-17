package com.msg.fillmap.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.dto.LoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.OidcLoginRequestDto;
import com.msg.fillmap.auth.dto.SignupRequestDto;
import com.msg.fillmap.auth.dto.SignupResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.service.AuthService;
import com.msg.fillmap.auth.service.OidcLoginService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.user.entity.AuthProvider;

@Tag(name = "인증 (Auth)", description = "회원가입·로그인·소셜 로그인 API. 이 그룹의 엔드포인트는 인증 없이 호출한다.")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private static final String BEARER_PREFIX = "Bearer ";

	private final AuthService authService;
	private final OidcLoginService oidcLoginService;

	@Operation(summary = "이메일 회원가입", description = "이메일/비밀번호/닉네임으로 신규 회원을 생성한다.")
	@PostMapping("/signup")
	public SuccessResponse<SignupResponseDto> signup(@Valid @RequestBody SignupRequestDto request) {
		return SuccessResponse.of(authService.signup(request));
	}

	@Operation(summary = "이메일 로그인", description = "이메일/비밀번호로 로그인하고 JWT 액세스 토큰을 발급받는다.")
	@PostMapping("/login")
	public SuccessResponse<LoginResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
		return SuccessResponse.of(authService.login(request));
	}

	@Operation(summary = "로그아웃", description = "Authorization 헤더의 액세스 토큰을 무효화한다.")
	@PostMapping("/logout")
	public SuccessResponse<Void> logout(
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			throw new ApiException(AuthErrorCode.INVALID_TOKEN);
		}
		authService.logout(authorization.substring(BEARER_PREFIX.length()));
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "소셜 로그인 (OIDC)",
		description = "소셜 제공자의 ID Token으로 로그인/가입하고 JWT 액세스 토큰을 발급받는다."
	)
	@PostMapping("/oauth/{provider}")
	public SuccessResponse<LoginResponseDto> oauthLogin(
		@Parameter(description = "소셜 제공자", example = "KAKAO") @PathVariable String provider,
		@Valid @RequestBody OidcLoginRequestDto request
	) {
		AuthProvider authProvider = parseProvider(provider);
		return SuccessResponse.of(oidcLoginService.login(authProvider, request.idToken()));
	}

	private AuthProvider parseProvider(String provider) {
		try {
			return AuthProvider.valueOf(provider.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ApiException(AuthErrorCode.UNSUPPORTED_PROVIDER);
		}
	}
}
