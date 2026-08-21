package com.msg.fillmap.event.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행사 위치 하나 (MSG-439 API 3). gridIds 는 FE 영역 채색 재료고, 영상은 그중 representativeGridId
 * 하나에만 붙는다 (FR-EVENT-08). 표시명 재료 세 필드는 대표 격자 기준이며 조립 규칙은 격자 응답 9종과 같다:
 * {@code zoneName ? zoneName + " " + zoneCell : regionName} (MSG-341·349).
 */
@Schema(description = "행사 위치 하나 — 영역 격자·대표 격자·표시명 재료·영상 수",
	requiredProperties = {"locationId", "name", "type", "operatingHours", "gridIds", "representativeGridId",
		"zoneName", "zoneCell", "regionName", "videoCount"})
public record EventLocationResponseDto(
	@Schema(description = "행사 위치 id — 위치별 영상 피드 진입 키", example = "31")
	Long locationId,

	@Schema(description = "위치 이름", example = "부산역 팝업")
	String name,

	@Schema(description = "위치 유형 — 표시 라벨 변환은 FE 몫", example = "POPUP",
		allowableValues = {"POPUP", "EXPERIENCE_ZONE", "PARADE", "PHOTO_ZONE", "ETC"})
	String type,

	@Schema(description = "운영 시간 표시 문자열", example = "11:00 ~ 20:00", nullable = true)
	String operatingHours,

	@Schema(description = "영역을 구성하는 격자 전체 — FE 영역 채색 재료", example = "[\"19443_9582\"]")
	List<String> gridIds,

	@Schema(description = "대표 격자 — 이 위치의 영상이 붙는 단 하나의 격자", example = "19443_9582")
	String representativeGridId,

	@Schema(description = "대표 격자가 속한 구역 이름. 구역 밖이면 null", example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 안 위치 코드. 구역 밖이면 null", example = "A-14", nullable = true)
	String zoneCell,

	@Schema(description = "대표 격자의 행정동 이름 — 구역 밖 표시명 폴백. 무귀속이면 null",
		example = "부전동", nullable = true)
	String regionName,

	@Schema(description = "이 위치의 영상 수 — 조회 시점 실측(전역 노출 게이트 통과분)", example = "7")
	Long videoCount
) {
}
