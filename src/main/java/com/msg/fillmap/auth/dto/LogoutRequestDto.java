package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 로그아웃 요청 body (MSG-178 logout 통합). 선택 body — 없으면 기존 동작 그대로(하위 호환).
 * fcmToken 이 있으면 세션 삭제와 같은 처리에서 해당 푸시 토큰도 정리된다.
 */
@Schema(description = "로그아웃 요청 (선택 body) — fcmToken 이 있으면 세션 삭제와 함께 해당 FCM 푸시 토큰도 정리된다")
public record LogoutRequestDto(
	@Schema(description = "정리할 FCM 토큰 (선택)", example = "fcm-token-abc123")
	String fcmToken
) {
}
