package com.msg.fillmap.auth.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.dto.LoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.SignupRequestDto;
import com.msg.fillmap.auth.dto.SignupResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.service.AuthService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.SuccessResponse;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private static final String BEARER_PREFIX = "Bearer ";

	private final AuthService authService;

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
}
