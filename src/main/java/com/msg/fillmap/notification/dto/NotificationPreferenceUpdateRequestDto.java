package com.msg.fillmap.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 카테고리 수신 토글 요청 (PATCH /api/notifications/preferences/{category} — MSG-180 FR-7).
 * category 는 body 가 아니라 경로 변수 String — 목록 외 값을 Jackson 파싱 400 이 아니라 도메인 코드
 * 10420 으로 주기 위해 서비스에서 대소문자 무시 파싱한다 (PushTokenRequestDto 의 platform 과 같은 논리).
 */
@Schema(description = "카테고리 수신 토글 요청 — 같은 값 재전환은 멱등하게 성공한다")
public record NotificationPreferenceUpdateRequestDto(
	@Schema(description = "수신 여부 — false 면 off(거부 행 저장), true 면 on(행 삭제)", example = "false")
	@NotNull
	Boolean enabled
) {
}
