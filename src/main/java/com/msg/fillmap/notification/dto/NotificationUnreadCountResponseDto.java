package com.msg.fillmap.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 안읽음 개수 응답 (GET /api/notifications/unread-count — MSG-434 FR-6). 지도 홈 프로필 빨간 점은
 * count &gt; 0 판정, 프로필 카드의 "새 알림 N개" 문구는 이 값으로 FE 가 조립한다.
 */
@Schema(description = "안읽은 알림 개수 — 목록과 같은 노출 조건(최근 30일·수신 거부 스킵 제외)",
	requiredProperties = {"count"})
public record NotificationUnreadCountResponseDto(
	@Schema(description = "안읽은 알림 개수 — 없으면 0", example = "3")
	long count
) {
}
