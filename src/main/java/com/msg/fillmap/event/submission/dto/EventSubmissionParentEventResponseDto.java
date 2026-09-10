package com.msg.fillmap.event.submission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 이벤트 참여형 신청이 참여할 부모 이벤트 회차 (MSG-502 §API 4). 이름의 원천은 {@code event_occurrences.title}
 * 하나라, 승인 이벤트 목록(MSG-501)에서 고른 이름과 상세에 보이는 이름이 어긋나지 않는다.
 */
@Schema(description = "참여할 부모 이벤트 회차", requiredProperties = {"occurrenceId", "name"})
public record EventSubmissionParentEventResponseDto(
	@Schema(description = "회차 id", example = "1")
	Long occurrenceId,

	@Schema(description = "이벤트 이름", example = "부산국제영화제")
	String name
) {
}
