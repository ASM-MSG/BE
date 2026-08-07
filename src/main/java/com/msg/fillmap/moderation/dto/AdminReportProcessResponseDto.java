package com.msg.fillmap.moderation.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.moderation.entity.Report;
import com.msg.fillmap.moderation.entity.ReportStatus;
import com.msg.fillmap.video.entity.VideoStatus;

/**
 * 신고 승인·기각 처리 결과 (MSG-195 FR-4·FR-6). 승인이면 status 는 RESOLVED, 기각이면 REJECTED 다.
 * videoStatus 는 처리 후 영상 상태다 — 승인의 전이 생략 케이스(이미 BLINDED, 또는 DELETED — FR-5)를
 * 관리자가 응답만으로 구분할 수 있고, 기각은 손대지 않은 현재 상태가 그대로 온다.
 */
@Schema(
	description = "신고 승인·기각 처리 결과 — 종결된 신고 상태와 처리 후 영상 상태.",
	// 처리 직후 응답이라 다섯 필드 전부 값이 있다 — nullable 필드 없음 (MSG-319 계약 가드).
	requiredProperties = {"reportId", "status", "videoId", "videoStatus", "reviewedAt"}
)
public record AdminReportProcessResponseDto(
	@Schema(description = "처리된 신고 ID", example = "7")
	Long reportId,

	@Schema(description = "처리 후 신고 상태 — 승인이면 RESOLVED, 기각이면 REJECTED", example = "RESOLVED")
	ReportStatus status,

	@Schema(description = "신고 대상 영상 ID", example = "1042")
	Long videoId,

	@Schema(description = "처리 후 영상 상태 — 승인의 전이 생략 케이스(FR-5)를 이 값으로 구분한다", example = "BLINDED")
	VideoStatus videoStatus,

	@Schema(description = "처리 시각", example = "2026-08-06T11:00:00")
	LocalDateTime reviewedAt
) {

	/** 처리(resolve·reject) 완료된 신고 엔티티에서 만든다 — 상태·처리 시각이 이미 기록된 뒤여야 한다. */
	public static AdminReportProcessResponseDto of(Report report, VideoStatus videoStatus) {
		return new AdminReportProcessResponseDto(
			report.getId(), report.getStatus(), report.getVideoId(), videoStatus, report.getReviewedAt());
	}
}
