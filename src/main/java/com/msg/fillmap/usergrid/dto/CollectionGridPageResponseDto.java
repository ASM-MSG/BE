package com.msg.fillmap.usergrid.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.usergrid.service.CollectionGridPage;

@Schema(description = "행정동 전체 보기 개인 격자 커서 페이지",
	requiredProperties = {"items", "hasNext", "nextCursor"})
public record CollectionGridPageResponseDto(
	@Schema(description = "최근 업로드순 개인 격자 카드. 한 페이지 최대 20개")
	List<CollectionGridResponseDto> items,

	@Schema(description = "다음 페이지 존재 여부")
	boolean hasNext,

	@Schema(description = "다음 페이지 요청의 cursor에 그대로 넣는 불투명 커서", nullable = true)
	String nextCursor
) {

	public static CollectionGridPageResponseDto from(CollectionGridPage page) {
		return new CollectionGridPageResponseDto(
			page.items().stream().map(CollectionGridResponseDto::from).toList(),
			page.hasNext(),
			page.nextCursor());
	}
}
