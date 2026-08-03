package com.msg.fillmap.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.auth.support.RefreshTokenCookies;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.user.dto.NicknameUpdateRequestDto;
import com.msg.fillmap.user.dto.UserProfileResponseDto;
import com.msg.fillmap.user.service.UserService;

@Tag(name = "사용자 (User)", description = "계정 관리 API. 인증 필수 — 본인 계정만 대상이다.")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

	private static final String BEARER_PREFIX = "Bearer ";

	private final UserService userService;

	@Operation(
		summary = "내 프로필 조회",
		description = "소셜 로그인이 자동 저장한 이메일·닉네임을 반환한다. "
			+ "항상 본인 계정만 — 경로에 대상 식별자가 없다."
	)
	@GetMapping("/me")
	public SuccessResponse<UserProfileResponseDto> getMe(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(userService.getMyProfile(principal.userId()));
	}

	@Operation(
		summary = "닉네임 수정",
		description = "닉네임(2~20자)을 교체하고 변경 후 프로필을 반환한다. 중복 닉네임은 허용된다."
	)
	@PutMapping("/me/nickname")
	public SuccessResponse<UserProfileResponseDto> updateNickname(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody NicknameUpdateRequestDto request
	) {
		return SuccessResponse.of(userService.updateNickname(principal.userId(), request.nickname()));
	}

	@Operation(
		summary = "계정 삭제",
		description = "내 계정을 즉시·비가역 삭제한다. 연쇄 개인 데이터·영상 S3 객체가 제거되고 "
			+ "전 디바이스 세션이 무효화된다. 같은 이메일·카카오 계정으로 다시 로그인하면 신규 가입이다."
	)
	@DeleteMapping("/me")
	public SuccessResponse<Void> deleteMe(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
		HttpServletResponse response
	) {
		// 엔드포인트가 authenticated 라 필터 통과(Bearer 형식)가 보장된다 — logout 같은 null 가드 불필요 (§D4).
		userService.deleteAccount(principal.userId(), authorization.substring(BEARER_PREFIX.length()));
		response.addHeader(HttpHeaders.SET_COOKIE, RefreshTokenCookies.expire());
		return new SuccessResponse<>(null);
	}
}
