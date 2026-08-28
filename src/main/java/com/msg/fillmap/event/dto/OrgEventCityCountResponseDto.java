package com.msg.fillmap.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 시·도 칩 하나의 건수 (MSG-501). 건수는 시·도 필터와 이름 검색을 적용하지 않은 전체 기준이다 —
 * 칩은 내비게이션이라 검색 중에 숫자가 흔들리면 구실을 못 한다. 시·도 이름은 회차 저장값 그대로라
 * 축약형("부산")이고, 정식 명칭 라벨이 필요하면 FE 표기 몫이다(기존 칩 API 와 같은 계약).
 */
@Schema(description = "시·도별 승인 이벤트 건수 — 모달 시·도 칩 재료",
	requiredProperties = {"cityName", "count"})
public record OrgEventCityCountResponseDto(
	@Schema(description = "시·도 이름 — city 필터에 그대로 넣는 값", example = "부산")
	String cityName,

	@Schema(description = "그 시·도의 승인 이벤트 수 (전체 기준)", example = "3")
	int count
) {
}
