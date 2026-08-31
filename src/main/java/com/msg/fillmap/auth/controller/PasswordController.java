package com.msg.fillmap.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.dto.PasswordChangeRequestDto;
import com.msg.fillmap.auth.dto.PasswordResetConfirmRequestDto;
import com.msg.fillmap.auth.dto.PasswordResetRequestDto;
import com.msg.fillmap.auth.dto.PasswordStatusResponseDto;
import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.auth.service.PasswordService;
import com.msg.fillmap.response.SuccessResponse;

@Tag(name = "비밀번호 (Password)", description = "비밀번호 상태·변경·재설정 API. 상태·변경은 로그인 필수, 재설정 2종은 비로그인이다.")
@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordController {

	private final PasswordService passwordService;

	@Operation(
		summary = "비밀번호 강제 변경 상태 조회",
		description = "true 면 초기 비밀번호 상태라 행사 등재 콘솔(/api/org/**)이 전부 막힌다. "
			+ "비밀번호가 없는 소셜 계정은 항상 false 다."
	)
	@GetMapping("/status")
	public SuccessResponse<PasswordStatusResponseDto> getStatus(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(passwordService.getStatus(principal.userId()));
	}

	@Operation(
		summary = "비밀번호 변경",
		description = "현재 비밀번호를 확인하고 새 비밀번호로 바꾼다. 성공하면 강제 변경 상태가 풀려 콘솔이 열리고, "
			+ "남아 있던 재설정 링크는 폐기된다. 로그인 중인 다른 기기의 세션은 그대로 유지된다.\n\n"
			+ "이메일·비밀번호로 만든 계정만 쓸 수 있다 — 소셜 로그인 계정은 2445 로 거절된다."
	)
	@PostMapping("/change")
	public SuccessResponse<Void> changePassword(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody PasswordChangeRequestDto request
	) {
		passwordService.changePassword(principal.userId(), request);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "비밀번호 재설정 링크 요청",
		description = "공식 이메일로 30분 동안 유효한 재설정 링크를 보낸다. 재요청하면 이전 링크는 즉시 무효가 된다.\n\n"
			+ "계정이 있든 없든 항상 같은 성공 응답이다 — 이 API 로 가입 여부를 알아낼 수 없게 하기 위해서다."
	)
	@PostMapping("/reset-request")
	public SuccessResponse<Void> requestReset(@Valid @RequestBody PasswordResetRequestDto request) {
		passwordService.requestReset(request);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "비밀번호 재설정 확정",
		description = "메일 링크의 토큰으로 새 비밀번호를 설정한다. 토큰은 한 번만 쓸 수 있다.\n\n"
			+ "성공하면 그 계정의 모든 기기 로그인이 끊기고, 이미 발급돼 있던 액세스 토큰도 즉시 무효가 된다 — "
			+ "비밀번호를 잊은 복구 흐름이라 기존 세션을 남기지 않는다."
	)
	@PostMapping("/reset")
	public SuccessResponse<Void> resetPassword(@Valid @RequestBody PasswordResetConfirmRequestDto request) {
		passwordService.resetPassword(request);
		return new SuccessResponse<>(null);
	}
}
