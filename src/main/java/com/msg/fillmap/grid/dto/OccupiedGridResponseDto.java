package com.msg.fillmap.grid.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.grid.service.OccupiedGridView;

/**
 * viewport 색칠 항목 (최소 필드). gridId + FE 렌더링용 정수 인덱스(gridY/gridX)만 담는다.
 * coverVideo 등 영상 확장은 MSG-90 소관 — 여기선 셀 위치만.
 */
@Schema(description = "뷰포트 색칠 격자 한 칸 — 지도 렌더링용 위치 정보")
public record OccupiedGridResponseDto(
	@Schema(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "41642_110458")
	String gridId,

	@Schema(description = "격자 세로 인덱스 (위도 기반 정수)", example = "41642")
	int gridY,

	@Schema(description = "격자 가로 인덱스 (경도 기반 정수)", example = "110458")
	int gridX
) {

	public static OccupiedGridResponseDto from(OccupiedGridView view) {
		return new OccupiedGridResponseDto(view.gridId(), view.gridY(), view.gridX());
	}
}
