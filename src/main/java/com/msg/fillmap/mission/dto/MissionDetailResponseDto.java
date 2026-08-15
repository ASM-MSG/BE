package com.msg.fillmap.mission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 미션 상세 응답 (MSG-399 §API 명세). 미션 정보는 목록과 같은 MissionResponseDto 를 중첩해 재사용하고
 * (같은 메타데이터·shape 를 상세 전용 타입에 복사하지 않는다), 진행도는 MSG-398 응답 타입 그대로다 —
 * 목록과 상세의 진행도 뜻을 한 곳에 둔다. 사용자별 값(진행도·방문 여부)이 들어가므로 전역 캐시에 넣지
 * 않는다. spotStats 는 COURSE 만 값이 있고 그 외 유형은 null 이 아니라 빈 배열이다.
 */
@Schema(description = "미션 상세 — 미션 정보 + 내 진행도 + 전체 영상 개수 + 코스 스팟별 통계",
	requiredProperties = {"mission", "progress", "videoCount", "spotStats"})
public record MissionDetailResponseDto(
	@Schema(description = "미션 정보 — 목록(GET /api/missions/active)과 같은 필드·shape")
	MissionResponseDto mission,

	@Schema(description = "내 진행도 — 목록 진행도(GET /api/missions/progress)와 같은 계산 (MSG-398 D8)")
	MissionProgressResponseDto progress,

	@Schema(description = "미션 기간 안에 촬영된 전역 공개(ACTIVE·PUBLIC·READY) 영상 수 — "
		+ "미션 영상 목록(MSG-390)의 실제 후보 수와 같다", example = "19")
	long videoCount,

	@Schema(description = "코스 포토스팟별 방문 여부·영상 개수 — shape.spots 와 같은 순서"
		+ "(seq ASC NULLS LAST, gridId ASC). 코스가 아니면 빈 배열")
	List<SpotStats> spotStats
) {

	/** 포토스팟 하나의 통계 — gridId 로 mission.shape.spots 에 대응한다 (이름은 MSG-397 몫, 중복 보관 안 함). */
	@Schema(description = "코스 포토스팟 하나의 방문 여부·영상 개수",
		requiredProperties = {"gridId", "visited", "videoCount"})
	public record SpotStats(
		@Schema(description = "포토스팟 격자 id — shape.spots 의 gridId 에 대응", example = "38677_114635")
		String gridId,

		@Schema(description = "미션 기간 안에 촬영한 내 영상이 있는지 — 진행도와 같은 술어", example = "true")
		boolean visited,

		@Schema(description = "이 스팟에 올라온 전역 공개 영상 수. 영상이 없으면 0", example = "9")
		long videoCount
	) {
	}
}
