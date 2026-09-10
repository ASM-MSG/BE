package com.msg.fillmap.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.VideoStatus;

/**
 * 블라인드 해제 결과 (MSG-195 FR-8). 해제는 영상 상태 복귀만이라 응답도 영상 축 두 필드뿐이다 —
 * 신고 상태는 되돌리지 않으므로(2026-08-06 확정) 신고 필드가 없다.
 */
@Schema(
	description = "블라인드 해제 결과 — 복구된 영상 상태.",
	// 해제 성공 응답이라 두 필드 전부 값이 있다 — nullable 필드 없음 (MSG-319 계약 가드).
	requiredProperties = {"videoId", "status"}
)
public record AdminVideoUnblindResponseDto(
	@Schema(description = "해제된 영상 ID", example = "1042")
	Long videoId,

	@Schema(description = "해제 후 영상 상태 — 성공이면 항상 ACTIVE", example = "ACTIVE")
	VideoStatus status
) {
}
