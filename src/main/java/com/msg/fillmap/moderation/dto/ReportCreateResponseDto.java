package com.msg.fillmap.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.moderation.entity.Report;

/**
 * 영상 신고 접수 응답 (MSG-192). reportId 는 이후 문의 대응의 추적 키고, status 는 접수 확인용이라
 * 항상 PENDING 이다 — 접수 시점에 다른 상태로 갈 경로가 없다.
 */
@Schema(description = "영상 신고 접수 응답.", requiredProperties = {"reportId", "status"})
public record ReportCreateResponseDto(
	@Schema(description = "접수된 신고 ID", example = "17")
	Long reportId,

	@Schema(description = "신고 처리 상태. 접수 직후라 항상 PENDING", example = "PENDING")
	String status
) {

	public static ReportCreateResponseDto from(Report report) {
		return new ReportCreateResponseDto(report.getId(), report.getStatus().name());
	}
}
