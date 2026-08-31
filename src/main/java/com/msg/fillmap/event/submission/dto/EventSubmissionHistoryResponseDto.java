package com.msg.fillmap.event.submission.dto;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.event.submission.entity.EventSubmissionReasonCode;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatusHistory;

/**
 * 상태 이력 한 줄 (MSG-498 FR-12). 재제출로 상태가 심사 중으로 돌아가도 과거 반려 행은 여기 그대로 남아
 * "어떤 이유로 반려됐었는지"를 계속 확인할 수 있다.
 */
@Schema(description = "신청 상태 이력 항목",
	requiredProperties = {"status", "reasonCodes", "reasonText", "changedAt"})
public record EventSubmissionHistoryResponseDto(
	@Schema(description = "전이 후 상태", example = "REJECTED")
	String status,

	@Schema(description = "반려 항목 코드 — 반려 행에만 있고 그 외에는 null", nullable = true)
	List<String> reasonCodes,

	@Schema(description = "반려 사유 본문 — 반려 행에만 있고 그 외에는 null", nullable = true)
	String reasonText,

	@Schema(description = "전이 시각 (UTC)", example = "2026-08-28T02:00:00Z")
	LocalDateTime changedAt
) {

	public static EventSubmissionHistoryResponseDto from(EventSubmissionStatusHistory history) {
		List<EventSubmissionReasonCode> codes = history.getReasonCodes();
		return new EventSubmissionHistoryResponseDto(
			history.getStatus().name(),
			codes == null ? null : codes.stream().map(Enum::name).toList(),
			history.getReasonText(),
			history.getCreatedAt());
	}
}
