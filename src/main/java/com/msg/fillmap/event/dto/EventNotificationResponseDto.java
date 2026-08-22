package com.msg.fillmap.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행사 회차 알림 구독 상태 (MSG-442). 요청 처리 후의 **노출 상태**이지 저장된 행의 존재 여부가 아니다 —
 * 종료된 회차는 구독 행이 아직 남아 있어도 false 다 (PRD §4.2 유예부터 자동 OFF).
 */
@Schema(description = "행사 알림 구독 상태", requiredProperties = {"enabled"})
public record EventNotificationResponseDto(
	@Schema(description = "구독 여부 — 구독 행 존재이면서 회차가 예정·진행 중일 때만 true", example = "true")
	boolean enabled
) {
}
