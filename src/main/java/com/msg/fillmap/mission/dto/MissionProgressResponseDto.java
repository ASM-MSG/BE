package com.msg.fillmap.mission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 미션 하나에 대한 내 진행도 (MSG-398 §API 명세). 목록 응답(MissionResponseDto)에 담지 않고 분리한
 * 이유는 목록이 사용자별 값 없이 전역 캐시로 서빙되기 때문이다(FR-MISSION-02). 단위 표기("0/1칸")는
 * 화면 몫 — 서버는 숫자만 준다.
 *
 * null 이 날 수 있는 필드가 없어 nullable 을 붙이지 않는다: 결과 행은 missions 행이 있을 때만 생기므로
 * missionId·targetCount 는 NOT NULL 컬럼에서 오고, filledCount 는 COUNT 집계라 최소 0 이며,
 * completed 는 원시 boolean 이다.
 */
@Schema(description = "미션 하나에 대한 내 진행도",
	requiredProperties = {"missionId", "targetCount", "filledCount", "completed"})
public record MissionProgressResponseDto(
	@Schema(description = "미션 id (missions.id)", example = "412")
	Long missionId,

	@Schema(description = "완료에 필요한 격자 수 (missions.target_count)", example = "1")
	Integer targetCount,

	@Schema(description = "그 미션 격자 중 기간 안에 촬영한 내 영상이 있는 칸 수. targetCount 를 넘지 않는다", example = "1")
	Integer filledCount,

	@Schema(description = "내 스탬프 보유 여부 (user_missions)", example = "true")
	boolean completed
) {
}
