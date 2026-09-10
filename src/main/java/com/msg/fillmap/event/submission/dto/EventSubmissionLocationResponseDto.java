package com.msg.fillmap.event.submission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 신청 상세의 위치 하나 (MSG-498 FR-12). 이름이 없으므로 화면은 순번과 표시명 재료로 식별한다.
 * 표시명 조립 규칙은 기존 계약 그대로 {@code zoneName ? zoneName + " " + zoneCell : regionName} 이다.
 */
@Schema(description = "신청 위치 상세",
	requiredProperties = {"order", "representativeGridId", "zoneName", "zoneCell", "regionName", "cellCount",
		"areaRects"})
public record EventSubmissionLocationResponseDto(
	@Schema(description = "위치 순번 — 제출 배열 순서대로 1부터", example = "1")
	int order,

	@Schema(description = "서버가 계산한 대표 격자 id", example = "16860_11512")
	String representativeGridId,

	@Schema(description = "구역 표시명 — 구역 밖이면 null", example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 안 칸 이름 — 구역 밖이면 null", example = "A-14", nullable = true)
	String zoneCell,

	@Schema(description = "행정동 이름 — 무귀속이면 null", example = "부산 수영구 광안동", nullable = true)
	String regionName,

	@Schema(description = "영역 합집합 칸 수 — 최대 81", example = "21")
	int cellCount,

	@Schema(description = "제출 원본 사각형 — 재제출 폼 프리필 재료라 보낸 그대로다")
	List<EventSubmissionAreaRectDto> areaRects
) {
}
