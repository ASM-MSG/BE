package com.msg.fillmap.grid.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.grid.service.GridCellView;

/**
 * 단일 격자 색칠 상태 응답. occupied = 내 점령 여부(user_grids row 존재), videoCount = 내 영상 수.
 * 미점령이면 occupied=false, videoCount=0 (격자는 항상 존재하는 논리 개념 — 404 아님).
 */
@Schema(description = "단일 격자의 내 색칠(점령) 상태. 미점령이어도 404가 아니라 occupied=false로 응답한다.")
public record GridCellResponseDto(
	@Schema(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "41642_110458")
	String gridId,

	@Schema(description = "내가 이 격자를 점령(색칠)했는지 여부", example = "true")
	boolean occupied,

	@Schema(description = "이 격자에 올린 내 영상 수 (미점령이면 0)", example = "3")
	Integer videoCount
) {

	public static GridCellResponseDto from(GridCellView view) {
		return new GridCellResponseDto(view.gridId(), view.occupied(), view.videoCount());
	}
}
