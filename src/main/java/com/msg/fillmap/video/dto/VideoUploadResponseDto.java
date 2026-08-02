package com.msg.fillmap.video.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.mission.dto.CompletedMissionResponseDto;

/**
 * 메타데이터 저장 응답. occupied = 이 업로드로 격자를 처음 점령했는지(첫 방문) 여부.
 * newBadges = 이 업로드로 새로 획득한 뱃지(MSG-239 FR-9) — 없으면 빈 배열.
 * completedMissions = 이 업로드로 완료된 미션 스탬프(MSG-223 FR-19) — 없으면 빈 배열.
 */
@Schema(description = "영상 메타데이터 저장 응답",
	requiredProperties = {"videoId", "gridId", "processingStatus", "occupied", "newBadges", "completedMissions"})
public record VideoUploadResponseDto(
	@Schema(description = "생성된 영상 ID", example = "1001")
	Long videoId,

	@Schema(description = "매핑된 격자 ID", example = "41642_110458")
	String gridId,

	@Schema(description = "영상 처리 상태 (UPLOADED/ENCODING/BLURRING/READY/FAILED)", example = "UPLOADED")
	String processingStatus,

	@Schema(description = "이 업로드로 격자를 처음 점령(첫 방문)했는지 여부", example = "true")
	boolean occupied,

	@Schema(description = "이 업로드로 새로 획득한 뱃지 목록 — 없으면 빈 배열")
	List<EarnedBadgeResponseDto> newBadges,

	@Schema(description = "이 업로드로 완료된 미션 스탬프 목록 — 없으면 빈 배열")
	List<CompletedMissionResponseDto> completedMissions
) {
}
