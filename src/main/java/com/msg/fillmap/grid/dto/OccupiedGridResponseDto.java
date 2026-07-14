package com.msg.fillmap.grid.dto;

import com.msg.fillmap.grid.service.OccupiedGridView;

/**
 * viewport 색칠 항목 (최소 필드). gridId + FE 렌더링용 정수 인덱스(gridY/gridX)만 담는다.
 * coverVideo 등 영상 확장은 MSG-90 소관 — 여기선 셀 위치만.
 */
public record OccupiedGridResponseDto(String gridId, int gridY, int gridX) {

	public static OccupiedGridResponseDto from(OccupiedGridView view) {
		return new OccupiedGridResponseDto(view.gridId(), view.gridY(), view.gridX());
	}
}
