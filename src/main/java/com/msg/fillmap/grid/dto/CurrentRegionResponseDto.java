package com.msg.fillmap.grid.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.grid.service.CurrentRegionView;

@Schema(description = "뷰포트 중심이 속한 현재 행정동과 개인 점령 요약",
	requiredProperties = {"regionCode", "name", "gridCount", "videoCount"})
public record CurrentRegionResponseDto(
	@Schema(description = "행정동 코드 10자리", example = "2623058000")
	String regionCode,

	@Schema(description = "동 이름 한 토큰", example = "부전2동")
	String name,

	@Schema(description = "이 행정동 전체에서 내가 점령한 격자 수(뷰포트 무관)", example = "5")
	int gridCount,

	@Schema(description = "이 행정동 전체에서 내 격자에 올린 영상 수(뷰포트 무관)", example = "355")
	long videoCount
) {

	public static CurrentRegionResponseDto from(CurrentRegionView view) {
		return new CurrentRegionResponseDto(view.regionCode(), view.name(), view.gridCount(), view.videoCount());
	}
}
