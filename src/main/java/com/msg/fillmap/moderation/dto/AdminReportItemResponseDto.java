package com.msg.fillmap.moderation.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.moderation.entity.ReportReason;
import com.msg.fillmap.moderation.entity.ReportStatus;
import com.msg.fillmap.video.entity.VideoStatus;

/**
 * 관리자 신고 목록 항목 (MSG-195 FR-2). 관리자가 목록만 보고 승인·기각을 판단할 수 있는 정보를 담는다 —
 * 사유·상세 설명·접수 시각에 더해 신고자와 영상 소유자의 닉네임까지. 처리 이력(reviewedBy·reviewedAt)이
 * 함께 있는 이유는 RESOLVED·REJECTED 필터 조회(FR-1)가 "누가 언제 처리했나" 없이는 반쪽이라서다.
 *
 * <p>JPQL 생성자 프로젝션 타깃 겸 응답 DTO (FriendListItemResponseDto 선례) — 필드 순서가 곧
 * {@code ReportRepository.findPageByStatus} 의 SELECT new 인자 순서다. 바꾸면 쿼리도 같이 바꿔야 한다.
 */
@Schema(
	description = "관리자 신고 목록 항목 — 신고 한 건과 판단에 필요한 주변 정보.",
	// 값이 null 일 수 있는 detail·reviewedBy·reviewedAt 도 required 다 — Jackson 기본 직렬화라 키는 항상
	// 나가고, OpenAPI required 는 "키 존재"라서 nullable 과 직교한다 (MSG-319 계약 가드).
	requiredProperties = {"reportId", "status", "reason", "detail", "createdAt", "reporterId", "reporterNickname",
		"videoId", "videoStatus", "videoOwnerNickname", "reviewedBy", "reviewedAt"}
)
public record AdminReportItemResponseDto(
	@Schema(description = "신고 ID — 승인·기각 경로 변수로 그대로 쓴다", example = "7")
	Long reportId,

	@Schema(description = "신고 처리 상태", example = "PENDING")
	ReportStatus status,

	@Schema(description = "신고 사유", example = "INAPPROPRIATE")
	ReportReason reason,

	@Schema(description = "신고자가 적은 상세 설명 — OTHER 가 아닌 사유는 없을 수 있다", nullable = true)
	String detail,

	@Schema(description = "신고 접수 시각", example = "2026-08-06T10:15:00Z")
	LocalDateTime createdAt,

	@Schema(description = "신고자의 사용자 ID", example = "3")
	Long reporterId,

	@Schema(description = "신고자의 닉네임", example = "정민")
	String reporterNickname,

	@Schema(description = "신고 대상 영상 ID — 단건 확인·블라인드 해제 경로 변수로 쓴다", example = "1042")
	Long videoId,

	@Schema(description = "신고 대상 영상의 현재 상태 (ACTIVE/BLINDED/DELETED)", example = "ACTIVE")
	VideoStatus videoStatus,

	@Schema(description = "영상 소유자의 닉네임", example = "성민")
	String videoOwnerNickname,

	@Schema(description = "처리한 관리자의 사용자 ID — 미처리면 null", nullable = true, example = "1")
	Long reviewedBy,

	@Schema(description = "처리 시각 — 미처리면 null", nullable = true, example = "2026-08-06T11:00:00Z")
	LocalDateTime reviewedAt
) {
}
