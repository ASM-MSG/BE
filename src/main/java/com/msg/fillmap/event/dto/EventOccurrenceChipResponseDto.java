package com.msg.fillmap.event.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventStatus;

/**
 * 지도 홈 행사 칩 하나 (MSG-439 API 1). FE 는 cityName 으로 시 칩을 묶고 그 아래 행사 칩을 나열한다.
 * D-day 는 startsAt 을 KST 로 읽어 FE 가 계산한다 — 서버는 재료만 준다.
 */
@Schema(description = "뷰포트에 걸친 행사 회차 하나 — 지도 홈 칩 재료",
	requiredProperties = {"occurrenceId", "title", "cityName", "startsAt", "endsAt", "status"})
public record EventOccurrenceChipResponseDto(
	@Schema(description = "행사 회차 id", example = "12")
	Long occurrenceId,

	@Schema(description = "행사명 — 칩 라벨 재료", example = "부산불꽃축제")
	String title,

	@Schema(description = "대상 지역 시 이름 — 시 칩 묶음 기준", example = "부산")
	String cityName,

	@Schema(description = "행사 시작 시각", example = "2026-10-06T01:00:00Z")
	LocalDateTime startsAt,

	@Schema(description = "행사 종료 시각", example = "2026-10-15T13:00:00Z")
	LocalDateTime endsAt,

	@Schema(description = "서버 시각 기준 파생 상태 — 이 목록에는 두 값만 담긴다",
		example = "LIVE", allowableValues = {"UPCOMING", "LIVE"})
	String status
) {

	public static EventOccurrenceChipResponseDto of(EventOccurrence occurrence, EventStatus status) {
		return new EventOccurrenceChipResponseDto(
			occurrence.getId(),
			occurrence.getTitle(),
			occurrence.getCityName(),
			occurrence.getStartsAt(),
			occurrence.getEndsAt(),
			status.name());
	}
}
