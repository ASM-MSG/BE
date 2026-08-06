package com.msg.fillmap.region.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.region.service.RegionStatView;

/**
 * 행정동별 수집률 응답 (MSG-156, RegionResponseDto 패턴 복제). region_stats 를 regions 와 조인한 한 행정동의 수집 현황.
 * progressRate 는 표시 100 clamp 값(§D4), updatedAt 은 region_stats 캐시 기준 시각.
 */
@Schema(description = "한 행정동의 수집률. 사용자가 그 행정동에서 점령(수집)한 격자 수와 진행률.",
	requiredProperties = {"regionCode", "regionName", "collectedCount", "totalCount", "progressRate", "updatedAt",
		"parentCode"})
public record RegionStatResponseDto(
	@Schema(description = "행정동 코드 (region_stats.region_code)", example = "1168051500")
	String regionCode,

	@Schema(description = "행정동 이름 (regions.region_name)", example = "서울특별시 강남구 역삼1동")
	String regionName,

	@Schema(description = "상위 시군구 코드 (regions.parent_code) — NULL 허용 컬럼이라 최상위 행은 null",
		example = "11680", nullable = true)
	String parentCode,

	@Schema(description = "점령(수집)한 격자 수", example = "5")
	Integer collectedCount,

	@Schema(description = "그 행정동 전체 격자 수(분모)", example = "20")
	Integer totalCount,

	@Schema(description = "수집률(%) — 100 상한 clamp", example = "25.00")
	BigDecimal progressRate,

	@Schema(description = "수집률 캐시 기준 시각", example = "2026-07-20T10:00:00")
	LocalDateTime updatedAt
) {

	public static RegionStatResponseDto from(RegionStatView view) {
		return new RegionStatResponseDto(
			view.regionCode(), view.regionName(), view.parentCode(),
			view.collectedCount(), view.totalCount(), view.progressRate(), view.updatedAt());
	}
}
