package com.msg.fillmap.event.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행사 운영자 콘솔의 승인 이벤트 목록 응답 (MSG-501). 모달 한 화면(시·도 칩, 건수, 목록)이 이 응답 하나로
 * 조립된다. {@code totalCount} 와 {@code cityCounts} 는 필터·검색을 적용하지 않은 전체 기준이고,
 * {@code events} 에만 요청 파라미터가 적용된다 — 검색 중에도 칩 건수가 고정이라 내비게이션이 성립한다.
 */
@Schema(description = "승인 이벤트 목록 — 참여 신청 모달 재료",
	requiredProperties = {"totalCount", "cityCounts", "events"})
public record OrgEventListResponseDto(
	@Schema(description = "승인 이벤트 전체 건수 — 필터·검색과 무관한 '전체 보기' 칩 재료", example = "4")
	int totalCount,

	@Schema(description = "시·도별 건수 — 건수 내림차순, 동수는 이름 오름차순")
	List<OrgEventCityCountResponseDto> cityCounts,

	@Schema(description = "필터·검색이 적용된 목록 — 시작일 오름차순, 동시각은 회차 id 오름차순")
	List<OrgEventItemResponseDto> events
) {
}
