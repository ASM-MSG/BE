package com.msg.fillmap.mission.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.mission.entity.Mission;

/**
 * 활성 미션 하나 (MSG-222 §API 명세). 공통 필드 + 유형별 shape 하나. FE 는 리스트를 순회하며 type 으로 렌더러를
 * 스위치한다(면/폴리라인/점/경계). per-user 필드 없음 — 모든 사용자에게 동일해 전역 캐시가 성립한다(§개요).
 */
@Schema(description = "활성 미션 하나 — 공통 필드 + 유형별 렌더 shape",
	requiredProperties = {"missionId", "type", "title", "targetCount", "shape", "startAt", "endAt"})
public record MissionResponseDto(
	@Schema(description = "미션 id (missions.id)", example = "12")
	Long missionId,

	@Schema(description = "미션 유형 — FE 렌더러 판별자",
		example = "COURSE", allowableValues = {"COURSE", "AREA", "EVENT", "THEME", "CONTINUOUS", "POPUP"})
	String type,

	@Schema(description = "미션 제목", example = "남파랑길 3코스")
	String title,

	@Schema(description = "완료에 필요한 distinct 방문 격자 수(표시·판정 힌트, 판정은 MSG-223)", example = "3")
	Integer targetCount,

	@Schema(description = "시작 시각. NULL = 무기간(상시)", example = "2026-11-01T00:00:00Z", nullable = true)
	LocalDateTime startAt,

	@Schema(description = "종료 시각. NULL = 무기간(상시)", example = "2026-11-01T23:59:59Z", nullable = true)
	LocalDateTime endAt,

	@Schema(description = "유형별 렌더 shape 하나(type 에 대응하는 PATH/BOX/CELLS/REGION)")
	MissionShape shape
) {

	/** shape 는 서비스가 유형별로 합성해 넘긴다(§도메인 3). */
	public static MissionResponseDto of(Mission mission, MissionShape shape) {
		return new MissionResponseDto(
			mission.getId(),
			mission.getType().name(),
			mission.getTitle(),
			mission.getTargetCount(),
			mission.getStartAt(),
			mission.getEndAt(),
			shape);
	}
}
