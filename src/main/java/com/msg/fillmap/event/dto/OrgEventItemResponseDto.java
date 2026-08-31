package com.msg.fillmap.event.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.event.entity.EventOccurrence;

/**
 * 행사 운영자 콘솔의 승인 이벤트 목록 항목 하나 (MSG-501). 이름은 시리즈 이름이 아니라 회차 제목이다 —
 * 사용자 대면 이름과 검색 대상이 같은 컬럼이라 보이는 이름과 찾는 이름이 어긋나지 않는다(칩 API 선례).
 * 기간의 날짜 라벨 변환은 FE 몫이고 서버는 시각 재료만 준다.
 */
@Schema(description = "승인 이벤트 하나 — 참여 신청(MSG-502)이 부모로 지정할 후보",
	requiredProperties = {"occurrenceId", "name", "cityName", "startsAt", "endsAt", "placeLabel"})
public record OrgEventItemResponseDto(
	@Schema(description = "행사 회차 id — 참여 신청의 부모 참조값", example = "1")
	Long occurrenceId,

	@Schema(description = "이벤트 이름 (회차 제목)", example = "부산국제영화제")
	String name,

	@Schema(description = "대상 지역 시·도 — 시·도 칩 묶음 기준", example = "부산")
	String cityName,

	@Schema(description = "행사 시작 시각", example = "2026-10-06T01:00:00Z")
	LocalDateTime startsAt,

	@Schema(description = "행사 종료 시각", example = "2026-10-15T13:00:00Z")
	LocalDateTime endsAt,

	@Schema(description = "장소 라벨 — 표시 순서가 가장 앞선 위치의 이름. 위치가 없는 회차면 null",
		example = "영화의전당", nullable = true)
	String placeLabel
) {

	public static OrgEventItemResponseDto of(EventOccurrence occurrence, String placeLabel) {
		return new OrgEventItemResponseDto(
			occurrence.getId(),
			occurrence.getTitle(),
			occurrence.getCityName(),
			occurrence.getStartsAt(),
			occurrence.getEndsAt(),
			placeLabel);
	}
}
