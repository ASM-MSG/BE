package com.msg.fillmap.event.submission.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.event.submission.entity.EventSubmission;

/** 내 신청 목록의 한 줄 (MSG-498 FR-11). 카드가 그리는 값만 담고 위치·이력은 상세에서 준다. */
@Schema(description = "내 신청 목록 항목",
	requiredProperties = {"id", "submissionNo", "type", "title", "status", "startsOn", "endsOn", "updatedAt"})
public record EventSubmissionSummaryResponseDto(
	@Schema(description = "신청 id", example = "7")
	Long id,

	@Schema(description = "신청 번호", example = "FM-2026-0007")
	String submissionNo,

	@Schema(description = "등록 유형", example = "FESTIVAL")
	String type,

	@Schema(description = "축제명 / 팝업명", example = "부산불꽃축제")
	String title,

	@Schema(description = "신청 상태", example = "REJECTED")
	String status,

	@Schema(description = "행사 시작일", example = "2026-11-07")
	LocalDate startsOn,

	@Schema(description = "행사 종료일", example = "2026-11-07")
	LocalDate endsOn,

	@Schema(description = "마지막 변경 시각 (UTC)", example = "2026-08-28T02:11:00Z")
	LocalDateTime updatedAt
) {

	public static EventSubmissionSummaryResponseDto from(EventSubmission submission) {
		return new EventSubmissionSummaryResponseDto(
			submission.getId(),
			submission.getSubmissionNo(),
			submission.getType().name(),
			submission.getTitle(),
			submission.getStatus().name(),
			submission.getStartsOn(),
			submission.getEndsOn(),
			submission.getUpdatedAt());
	}
}
