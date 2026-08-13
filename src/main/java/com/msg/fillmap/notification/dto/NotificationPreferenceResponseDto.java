package com.msg.fillmap.notification.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.notification.entity.NotificationCategory;

/**
 * 알림 설정 응답 (GET/PATCH /api/notifications/preferences — MSG-180 FR-7). 항상 전 카테고리
 * (NotificationCategory 5종)를 enum 선언 순으로 반환한다 — 저장 행 없는 카테고리는 true 합성이라
 * FE 는 부재 처리가 필요 없다. 응답의 category 는 enum 직렬화(출력엔 파싱 실패가 없다).
 */
@Schema(description = "알림 설정 — 전 카테고리(5종)의 수신 상태 (저장 행 없는 카테고리는 true)",
	requiredProperties = {"preferences"})
public record NotificationPreferenceResponseDto(
	@Schema(description = "카테고리별 수신 상태 (BADGE·HOTZONE·REMIND·VIDEO·WEEKLY 고정 5종)")
	List<CategoryPreferenceDto> preferences
) {

	@Schema(description = "카테고리 하나의 수신 상태", requiredProperties = {"category", "enabled"})
	public record CategoryPreferenceDto(
		@Schema(description = "알림 카테고리", example = "HOTZONE")
		NotificationCategory category,

		@Schema(description = "수신 여부 — off 행 부재면 true (opt-out 기본 전부 on)", example = "true")
		boolean enabled
	) {
	}
}
