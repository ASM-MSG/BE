package com.msg.fillmap.grid.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.grid.service.GridAggregationView;

@Schema(description = "뷰포트 점령 격자 묶음과 현재 동네 집계")
public record GridAggregationResponseDto(
	@Schema(
		description = "뷰포트 중심이 속한 행정동. 해상이나 서비스 범위 밖이면 null",
		nullable = true
	)
	CurrentRegionResponseDto currentRegion,

	@Schema(description = "뷰포트 안에서 행정 단위로 묶은 내 점령 격자 목록")
	List<RegionAggregateResponseDto> items
) {

	public static GridAggregationResponseDto from(GridAggregationView view) {
		CurrentRegionResponseDto currentRegion = view.currentRegion() == null
			? null
			: CurrentRegionResponseDto.from(view.currentRegion());
		return new GridAggregationResponseDto(currentRegion,
			view.items().stream().map(RegionAggregateResponseDto::from).toList());
	}
}
