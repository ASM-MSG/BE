package com.msg.fillmap.grid.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.grid.service.OccupiedGridPage;

/**
 * viewport 색칠 페이지 응답 (MSG-90). grids 는 (grid_y, grid_x) 오름차순 정렬된 한 페이지,
 * nextCursor 는 다음 페이지 opaque 커서 — 마지막 페이지면 null.
 */
@Schema(description = "뷰포트 색칠 격자 페이지 응답 (커서 페이지네이션)", requiredProperties = {"grids", "nextCursor"})
public record OccupiedGridPageResponseDto(
	@Schema(description = "이 페이지의 색칠 격자 목록 ((grid_y, grid_x) 오름차순)")
	List<OccupiedGridResponseDto> grids,

	@Schema(description = "다음 페이지 조회용 커서. 다음 요청 cursor 파라미터에 넣는다. 마지막 페이지면 null.",
		example = "NDE2NDNfMTEwNDYw", nullable = true)
	String nextCursor
) {

	public static OccupiedGridPageResponseDto from(OccupiedGridPage page) {
		List<OccupiedGridResponseDto> grids = page.items().stream()
			.map(OccupiedGridResponseDto::from)
			.toList();
		return new OccupiedGridPageResponseDto(grids, page.nextCursor());
	}
}
