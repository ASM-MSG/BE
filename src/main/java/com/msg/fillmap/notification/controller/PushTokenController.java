package com.msg.fillmap.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.notification.dto.PushTokenRequestDto;
import com.msg.fillmap.notification.service.PushTokenService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * FCM 푸시 토큰 API (MSG-178). 3-layer 얇게 — principal userId + 서비스 호출 + SuccessResponse 변환만.
 * 클라 규약: 로그인 직후 POST, 로그아웃은 /api/auth/logout body 에 fcmToken 을 실어 한 번에 (logout 통합).
 */
@Tag(name = "알림 (Notification)", description = "FCM 푸시 토큰 등록/갱신·해제 API.")
@RestController
@RequestMapping("/api/notifications/tokens")
@RequiredArgsConstructor
public class PushTokenController {

	private final PushTokenService pushTokenService;

	@Operation(
		summary = "FCM 토큰 등록/갱신",
		description = "디바이스의 FCM 토큰을 현재 계정으로 등록한다(UPSERT). 같은 토큰 재등록은 충돌 없이 "
			+ "user_id·platform·appVersion·last_used_at 이 갱신된다 — 재로그인·계정 전환 포함. "
			+ "platform 이 IOS/ANDROID/WEB(대소문자 무시) 외면 10400 이다."
	)
	@PostMapping
	public SuccessResponse<Void> register(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody PushTokenRequestDto request
	) {
		pushTokenService.register(principal.userId(), request);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "FCM 토큰 해제",
		description = "본인 소유(user_id 일치) 토큰 행을 삭제한다 — 멱등, 없는 토큰·소유 불일치 해제도 200. "
			+ "로그아웃은 /api/auth/logout body 의 fcmToken 으로 한 번에 처리하고, 이 API 는 토큰 로테이션 등 "
			+ "로그아웃 외 정리 용도다."
	)
	@DeleteMapping
	public SuccessResponse<Void> unregister(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "해제할 FCM 토큰") @RequestParam String fcmToken
	) {
		pushTokenService.unregister(principal.userId(), fcmToken);
		return new SuccessResponse<>(null);
	}
}
