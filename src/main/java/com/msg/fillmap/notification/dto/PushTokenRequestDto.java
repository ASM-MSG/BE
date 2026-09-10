package com.msg.fillmap.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * FCM 푸시 토큰 등록/갱신 요청 (POST /api/notifications/tokens — MSG-178 FR-1).
 * platform 을 enum 이 아니라 String 으로 받는 이유: 잘못된 값을 Jackson 파싱 400(무의미한 메시지)이
 * 아니라 도메인 코드 10400 으로 주기 위해 — 서비스에서 대소문자 무시 파싱한다.
 */
@Schema(description = "FCM 푸시 토큰 등록/갱신 요청 — 같은 토큰 재등록은 충돌 없이 현재 계정으로 갱신된다")
public record PushTokenRequestDto(
	@Schema(description = "FCM 디바이스 토큰 (push_tokens PK, 최대 512자)", example = "fcm-token-abc123")
	@NotBlank
	@Size(max = 512)
	String fcmToken,

	@Schema(description = "플랫폼 — IOS·ANDROID·WEB (대소문자 무시)", example = "WEB")
	@NotBlank
	String platform,

	@Schema(description = "앱 버전 (선택, 최대 20자)", example = "1.0.0")
	@Size(max = 20)
	String appVersion
) {
}
