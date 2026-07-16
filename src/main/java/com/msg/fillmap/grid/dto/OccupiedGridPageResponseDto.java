package com.msg.fillmap.grid.dto;

import java.util.List;

import com.msg.fillmap.grid.service.OccupiedGridPage;

/**
 * viewport 색칠 페이지 응답 (MSG-90). grids 는 (grid_y, grid_x) 오름차순 정렬된 한 페이지,
 * nextCursor 는 다음 페이지 opaque 커서 — 마지막 페이지면 null.
 */
public record OccupiedGridPageResponseDto(List<OccupiedGridResponseDto> grids, String nextCursor) {

	public static OccupiedGridPageResponseDto from(OccupiedGridPage page) {
		List<OccupiedGridResponseDto> grids = page.items().stream()
			.map(OccupiedGridResponseDto::from)
			.toList();
		return new OccupiedGridPageResponseDto(grids, page.nextCursor());
	}
}
