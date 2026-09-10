package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.repository.RegionGridCountProjection;

/**
 * 전체 지역 리스트 항목 (MSG-238). 게이트 통과 격자가 1개 이상인 행정동 하나다.
 * 검색 무입력 드롭다운의 "어디에 콘텐츠가 많은가" 발견용이다. MSG-460부터 응답 바깥 페이지가
 * 개인 최근 업로드 지역을 먼저 정렬하며, 이 항목의 gridCount 정의는 기존 전역 공개 게이트를 유지한다.
 */
@Schema(description = "전체 지역 리스트 항목 (행정동별 격자 수)",
	requiredProperties = {"regionCode", "regionName", "gridCount"})
public record RegionGridCountResponseDto(
	@Schema(description = "행정동 코드 — 선택 시 격자 카드 조회에 전달", example = "2644056000")
	String regionCode,

	@Schema(description = "행정동 이름", example = "부산광역시 부산진구 부전2동")
	String regionName,

	@Schema(description = "그 행정동의 게이트 통과 격자 수", example = "5")
	Integer gridCount
) {

	public static RegionGridCountResponseDto of(RegionGridCountProjection projection) {
		return new RegionGridCountResponseDto(
			projection.getRegionCode(),
			projection.getRegionName(),
			projection.getGridCount());
	}
}
