package com.msg.fillmap.auth.controller;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.dto.DevSocialLoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.oidc.OidcUserInfo;
import com.msg.fillmap.auth.service.OidcLoginService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.user.entity.AuthProvider;

/**
 * [로컬/dev 전용] 소셜 로그인 모의 컨트롤러 (MSG-135).
 *
 * <p>실제 소셜 ID Token 검증 없이 (provider, oid)로 사용자를 find-or-create 하고 토큰을 발급해,
 * 프론트 OAuth 플로우 없이 백엔드에서 소셜 로그인·리프레시 흐름을 테스트한다.
 * 리프레시 토큰은 body 로 내려간다(앱 모드 — curl/Swagger 로 바로 확인 가능).
 * {@code @Profile({"local","dev"})} 이므로 운영(prod)에는 이 빈이 존재하지 않는다.
 */
@Profile({"local", "dev"})
@Tag(name = "인증-개발용 (Auth Dev)", description = "로컬/dev 전용 — 소셜 로그인을 실제 소셜 토큰 없이 백엔드에서 테스트. 운영(prod) 미노출.")
@RestController
@RequestMapping("/api/auth/dev")
@RequiredArgsConstructor
public class DevAuthController {

	private static final String DEVICE_ID_HEADER = "X-Device-Id";

	private final OidcLoginService oidcLoginService;

	@Operation(
		summary = "[개발용] 소셜 로그인 모의",
		description = "실제 OIDC ID Token 검증 없이 (provider, oid)로 사용자를 find-or-create 하고 "
			+ "액세스+리프레시 토큰을 발급한다. 리프레시는 body 로 내려간다(앱 모드). 로컬/dev 프로파일에서만 노출."
	)
	@PostMapping("/social-login")
	public SuccessResponse<LoginResponseDto> socialLogin(
		@Valid @RequestBody DevSocialLoginRequestDto request,
		@Parameter(description = "디바이스 식별자. 없으면 서버가 UUID 를 생성해 응답 헤더 X-Device-Id 로 반환.")
		@RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceIdHeader,
		HttpServletResponse response
	) {
		String deviceId = (deviceIdHeader == null || deviceIdHeader.isBlank())
			? UUID.randomUUID().toString() : deviceIdHeader;
		AuthProvider provider = parseProvider(request.provider());
		OidcUserInfo info = new OidcUserInfo(request.oid(), request.resolvedEmail(), request.resolvedNickname());
		LoginResponseDto issued = oidcLoginService.issueForOidcUser(provider, info, deviceId);
		response.setHeader(DEVICE_ID_HEADER, deviceId);
		return SuccessResponse.of(issued);
	}

	private AuthProvider parseProvider(String provider) {
		if (provider == null || provider.isBlank()) {
			return AuthProvider.KAKAO;
		}
		try {
			return AuthProvider.valueOf(provider.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ApiException(AuthErrorCode.UNSUPPORTED_PROVIDER);
		}
	}
}
