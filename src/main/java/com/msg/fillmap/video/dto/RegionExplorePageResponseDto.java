package com.msg.fillmap.video.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.service.RegionExplorePage;

@Schema(description = "전체 지역 개인화 커서 페이지",
	requiredProperties = {"items", "hasNext", "nextCursor"})
public record RegionExplorePageResponseDto(
	@Schema(description = "현재 페이지 행정동 목록. 최대 20개")
	List<RegionGridCountResponseDto> items,

	@Schema(description = "다음 페이지 존재 여부")
	boolean hasNext,

	@Schema(description = "다음 요청에 그대로 전달할 불투명 커서", nullable = true)
	String nextCursor
) {

	public static RegionExplorePageResponseDto from(RegionExplorePage page) {
		return new RegionExplorePageResponseDto(page.items(), page.hasNext(), page.nextCursor());
	}
}
