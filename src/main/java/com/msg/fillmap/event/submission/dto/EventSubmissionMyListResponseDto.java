package com.msg.fillmap.event.submission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 내 신청 목록 응답 (MSG-498 FR-11). 페이지네이션 없음 — 내부 소수 사용자다. 정렬은 최신 제출 순이다. */
@Schema(description = "내 신청 목록과 상태별 건수", requiredProperties = {"counts", "submissions"})
public record EventSubmissionMyListResponseDto(
	@Schema(description = "상태별 건수 — 내 신청 전체 기준")
	EventSubmissionStatusCountsResponseDto counts,

	@Schema(description = "신청 목록 — 최신 제출 순")
	List<EventSubmissionSummaryResponseDto> submissions
) {
}
