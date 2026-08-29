package com.msg.fillmap.event.submission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.event.submission.entity.EventSubmission;

/** 제출·재제출 응답 (MSG-498). 재제출도 같은 형태다 — 신청 번호는 불변이고 상태만 심사 중으로 돌아간다. */
@Schema(description = "신청 접수 결과", requiredProperties = {"id", "submissionNo", "status"})
public record EventSubmissionSubmitResponseDto(
	@Schema(description = "신청 id", example = "7")
	Long id,

	@Schema(description = "신청 번호 — FM-{KST 연도}-{4자리 순번}", example = "FM-2026-0007")
	String submissionNo,

	@Schema(description = "신청 상태", example = "IN_REVIEW")
	String status
) {

	public static EventSubmissionSubmitResponseDto from(EventSubmission submission) {
		return new EventSubmissionSubmitResponseDto(
			submission.getId(), submission.getSubmissionNo(), submission.getStatus().name());
	}
}
