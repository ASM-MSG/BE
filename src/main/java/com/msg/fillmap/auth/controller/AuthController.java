package com.msg.fillmap.auth.controller;

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

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private static final String BEARER_PREFIX = "Bearer ";

	private final AuthService authService;
	private final OidcLoginService oidcLoginService;

	@PostMapping("/signup")
	public SuccessResponse<SignupResponseDto> signup(@Valid @RequestBody SignupRequestDto request) {
		return SuccessResponse.of(authService.signup(request));
	}

	@PostMapping("/login")
	public SuccessResponse<LoginResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
		return SuccessResponse.of(authService.login(request));
	}

	@PostMapping("/logout")
	public SuccessResponse<Void> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
		if (!authorization.startsWith(BEARER_PREFIX)) {
			throw new ApiException(AuthErrorCode.INVALID_TOKEN);
		}
		authService.logout(authorization.substring(BEARER_PREFIX.length()));
		return new SuccessResponse<>(null);
	}

	@PostMapping("/oauth/{provider}")
	public SuccessResponse<LoginResponseDto> oauthLogin(
		@PathVariable String provider,
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
