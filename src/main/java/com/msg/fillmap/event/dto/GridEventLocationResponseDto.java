package com.msg.fillmap.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 격자가 속한 행사 위치 하나 (MSG-439 API 4). 영상은 대표 격자에만 저장되므로 이 역조회가 위치별 피드
 * 진입의 유일 경로다 (FR-EVENT-08). 배열 첫 항목이 진입 기본값이라 정렬이 서버 계약이다.
 */
@Schema(description = "격자 역조회 결과 하나 — 회차와 해석된 행사 위치",
	requiredProperties = {"occurrenceId", "occurrenceTitle", "occurrenceStatus", "locationId", "locationName",
		"representativeGridId", "zoneName", "zoneCell", "regionName"})
public record GridEventLocationResponseDto(
	@Schema(description = "소속 행사 회차 id", example = "12")
	Long occurrenceId,

	@Schema(description = "행사명", example = "부산불꽃축제")
	String occurrenceTitle,

	@Schema(description = "서버 시각 기준 파생 상태 — 상세와 같은 계산", example = "LIVE",
		allowableValues = {"UPCOMING", "LIVE", "UPLOAD_GRACE", "ARCHIVED"})
	String occurrenceStatus,

	@Schema(description = "해석된 행사 위치 id — 피드 진입 키", example = "31")
	Long locationId,

	@Schema(description = "위치 이름", example = "부산역 팝업")
	String locationName,

	@Schema(description = "대표 격자 — 피드(MSG-440)가 영상을 붙일 격자", example = "19443_9582")
	String representativeGridId,

	@Schema(description = "대표 격자가 속한 구역 이름. 구역 밖이면 null", example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 안 위치 코드. 구역 밖이면 null", example = "A-14", nullable = true)
	String zoneCell,

	@Schema(description = "대표 격자의 행정동 이름 — 구역 밖 표시명 폴백. 무귀속이면 null",
		example = "부전동", nullable = true)
	String regionName
) {
}
