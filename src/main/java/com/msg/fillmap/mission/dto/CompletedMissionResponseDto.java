package com.msg.fillmap.mission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.mission.repository.CompletedMissionProjection;

/**
 * 완료된 미션 스탬프 (업로드 응답 동봉용 — FR-19). 획득 연출에 필요한 최소만 노출한다 —
 * 내부 판정값(target_count 등)은 비노출(EarnedBadgeResponseDto 선례, MSG-223 §API 명세).
 */
@Schema(description = "이번 업로드로 완료된 미션 스탬프", requiredProperties = {"missionId", "title", "type"})
public record CompletedMissionResponseDto(
	@Schema(description = "미션 ID", example = "3")
	Long missionId,

	@Schema(description = "미션 제목", example = "성수 골목 코스")
	String title,

	@Schema(description = "미션 유형 (COURSE/AREA/EVENT/THEME/CONTINUOUS)", example = "COURSE")
	String type
) {

	public static CompletedMissionResponseDto from(CompletedMissionProjection projection) {
		return new CompletedMissionResponseDto(
			projection.getMissionId(), projection.getTitle(), projection.getType());
	}
}
