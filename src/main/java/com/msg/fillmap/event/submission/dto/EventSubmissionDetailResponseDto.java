package com.msg.fillmap.event.submission.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 신청 상세 (MSG-498 FR-12). 없는 신청과 남의 신청은 여기 오지 못하고 같은 13430 을 받는다 (FR-14).
 * 저장 S3 키는 내부 값이라 노출하지 않고 열람은 presigned GET 하나로 한다.
 */
@Schema(description = "신청 상세",
	requiredProperties = {"id", "submissionNo", "type", "status", "title", "organizerName", "startsOn", "endsOn",
		"operatingHours", "programDescription", "description", "imageUrl", "locations", "rejection", "history",
		"updatedAt"})
public record EventSubmissionDetailResponseDto(
	@Schema(description = "신청 id", example = "7")
	Long id,

	@Schema(description = "신청 번호", example = "FM-2026-0007")
	String submissionNo,

	@Schema(description = "등록 유형", example = "FESTIVAL")
	String type,

	@Schema(description = "신청 상태", example = "REJECTED")
	String status,

	@Schema(description = "축제명 / 팝업명")
	String title,

	@Schema(description = "주최 기관 / 브랜드·운영사")
	String organizerName,

	@Schema(description = "행사 시작일", example = "2026-11-07")
	LocalDate startsOn,

	@Schema(description = "행사 종료일", example = "2026-11-07")
	LocalDate endsOn,

	@Schema(description = "운영 시간 — POPUP 만 값이 있다", nullable = true)
	String operatingHours,

	@Schema(description = "주요 프로그램 — FESTIVAL 만 값이 있다", nullable = true)
	String programDescription,

	@Schema(description = "행사 소개")
	String description,

	@Schema(description = "대표 이미지 열람용 presigned GET URL")
	String imageUrl,

	@Schema(description = "위치 목록 — 순번 오름차순")
	List<EventSubmissionLocationResponseDto> locations,

	@Schema(description = "현재 반려 사유 — 상태가 REJECTED 일 때만 값이 있다", nullable = true)
	EventSubmissionRejectionResponseDto rejection,

	@Schema(description = "상태 이력 — 발생 순")
	List<EventSubmissionHistoryResponseDto> history,

	@Schema(description = "마지막 변경 시각 (UTC)", example = "2026-08-28T02:11:00Z")
	LocalDateTime updatedAt
) {
}
