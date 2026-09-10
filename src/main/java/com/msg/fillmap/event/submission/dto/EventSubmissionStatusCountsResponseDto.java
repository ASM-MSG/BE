package com.msg.fillmap.event.submission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 콘솔 홈 현황 카드의 상태별 건수 (MSG-498 FR-11). 해당 상태가 없으면 0 이다 (필드 자체는 항상 있다). */
@Schema(description = "내 신청의 상태별 건수", requiredProperties = {"inReview", "approved", "rejected"})
public record EventSubmissionStatusCountsResponseDto(
	@Schema(description = "심사 중 건수", example = "2")
	long inReview,

	@Schema(description = "승인 건수", example = "1")
	long approved,

	@Schema(description = "반려 건수", example = "1")
	long rejected
) {
}
