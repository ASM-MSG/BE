package com.msg.fillmap.event.submission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.event.submission.entity.EventSubmissionReasonCode;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatusHistory;

/**
 * 현재 반려 사유 (MSG-498 FR-12). 현재 상태가 REJECTED 일 때만 값이 있고 아니면 null 이다.
 * 값의 출처는 이력의 최신 행 하나다 — 신청 행에 사유를 중복 저장하지 않는다 (D-3).
 */
@Schema(description = "반려 항목과 사유", requiredProperties = {"reasonCodes", "reasonText"})
public record EventSubmissionRejectionResponseDto(
	@Schema(description = "반려 항목 코드 — PERIOD, AREA, IMAGE, INFO", example = "[\"AREA\", \"INFO\"]")
	List<String> reasonCodes,

	@Schema(description = "반려 사유 본문")
	String reasonText
) {

	/** 이력의 반려 행 하나에서 만든다 — 사유의 저장 원천이 이력 테이블 하나라서다 (D-3). */
	public static EventSubmissionRejectionResponseDto from(EventSubmissionStatusHistory history) {
		List<EventSubmissionReasonCode> codes = history.getReasonCodes();
		return new EventSubmissionRejectionResponseDto(
			codes == null ? null : codes.stream().map(Enum::name).toList(), history.getReasonText());
	}
}
