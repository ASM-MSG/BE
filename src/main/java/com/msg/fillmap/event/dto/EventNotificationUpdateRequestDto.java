package com.msg.fillmap.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/** 행사 회차 알림 구독 토글 요청 (MSG-442, PUT /api/event-occurrences/{occurrenceId}/notification). */
@Schema(description = "행사 알림 구독 토글", requiredProperties = {"enabled"})
public record EventNotificationUpdateRequestDto(
	@Schema(description = "구독 여부 — true 면 ON, false 면 OFF", example = "true")
	@NotNull
	Boolean enabled
) {
}
