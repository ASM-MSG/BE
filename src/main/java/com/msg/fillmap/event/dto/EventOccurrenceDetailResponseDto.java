package com.msg.fillmap.event.dto;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventStatus;

/**
 * 행사방 헤더 (MSG-439 API 2). status 와 uploadClosesAt 은 저장 컬럼이 아니라 서버 시각 기준 파생값이다
 * ({@link EventOccurrence#statusAt} · {@link EventOccurrence#uploadClosesAt}).
 * notificationOn 도 저장값이 아니라 파생값이다 — 구독 행 존재 AND 상태가 예정·진행 중 (MSG-442).
 * 열람 인원(MSG-443)은 이 응답의 범위 밖이다.
 */
@Schema(description = "행사 회차 상세 — 이벤트 헤더",
	requiredProperties = {"occurrenceId", "seriesId", "title", "startsAt", "endsAt", "uploadClosesAt",
		"status", "notificationOn", "previousOccurrences", "imageUrl"})
public record EventOccurrenceDetailResponseDto(
	@Schema(description = "행사 회차 id", example = "12")
	Long occurrenceId,

	@Schema(description = "행사 시리즈 id — 이전 회차 묶음 기준", example = "3")
	Long seriesId,

	@Schema(description = "행사명", example = "부산불꽃축제")
	String title,

	@Schema(description = "행사 시작 시각", example = "2026-10-06T01:00:00Z")
	LocalDateTime startsAt,

	@Schema(description = "행사 종료 시각", example = "2026-10-15T13:00:00Z")
	LocalDateTime endsAt,

	@Schema(description = "영상 업로드 마감 — 종료 30일 후 파생값", example = "2026-11-14T13:00:00Z")
	LocalDateTime uploadClosesAt,

	@Schema(description = "서버 시각 기준 파생 상태", example = "LIVE",
		allowableValues = {"UPCOMING", "LIVE", "UPLOAD_GRACE", "ARCHIVED"})
	String status,

	@Schema(description = "알림 구독 여부 — 구독 행 존재이면서 회차가 예정·진행 중일 때만 true. 비로그인은 항상 false 고, "
		+ "종료된 회차는 구독 행이 남아 있어도 false 다",
		example = "false")
	Boolean notificationOn,

	@Schema(description = "같은 시리즈의 지난 회차 — 최신순. 없으면 빈 배열")
	List<PreviousOccurrenceDto> previousOccurrences,

	@Schema(description = "대표 이미지 공개 URL — 이미지가 없는 회차는 null 이고 나머지 필드는 그대로다",
		nullable = true)
	String imageUrl
) {

	/** 이미지는 키만 저장하고 공개 주소는 여기서 조립한다 (MSG-538 D2, 행사 위치와 같은 계약). */
	public static EventOccurrenceDetailResponseDto of(
		EventOccurrence occurrence, EventStatus status, boolean notificationOn,
		List<PreviousOccurrenceDto> previousOccurrences, String imageUrl) {
		return new EventOccurrenceDetailResponseDto(
			occurrence.getId(),
			occurrence.getSeries().getId(),
			occurrence.getTitle(),
			occurrence.getStartsAt(),
			occurrence.getEndsAt(),
			occurrence.uploadClosesAt(),
			status.name(),
			notificationOn,
			previousOccurrences,
			imageUrl);
	}

	/**
	 * 지난 회차 한 줄. 그 회차의 위치·영상은 이 id 로 위치 목록(API 3)과 피드(MSG-440)를 다시 부르면 되므로
	 * 회차 간 데이터가 섞일 경로 자체가 없다.
	 */
	@Schema(description = "같은 시리즈의 지난 회차 하나",
		requiredProperties = {"occurrenceId", "title", "startsAt", "endsAt"})
	public record PreviousOccurrenceDto(
		@Schema(description = "행사 회차 id", example = "9")
		Long occurrenceId,

		@Schema(description = "행사명", example = "부산불꽃축제")
		String title,

		@Schema(description = "행사 시작 시각", example = "2025-10-04T01:00:00Z")
		LocalDateTime startsAt,

		@Schema(description = "행사 종료 시각", example = "2025-10-13T13:00:00Z")
		LocalDateTime endsAt
	) {

		public static PreviousOccurrenceDto from(EventOccurrence occurrence) {
			return new PreviousOccurrenceDto(
				occurrence.getId(),
				occurrence.getTitle(),
				occurrence.getStartsAt(),
				occurrence.getEndsAt());
		}
	}
}
