package com.msg.fillmap.grid.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.grid.service.GridCellView;

/**
 * 단일 격자 색칠 상태 응답. occupied = 내 점령 여부(user_grids row 존재), videoCount = 내 영상 수.
 * 미점령이면 occupied=false, videoCount=0 (격자는 항상 존재하는 논리 개념 — 404 아님).
 */
@Schema(description = "단일 격자의 내 색칠(점령) 상태. 미점령이어도 404가 아니라 occupied=false로 응답한다.",
	requiredProperties = {"gridId", "occupied", "videoCount", "zoneName", "zoneCell", "regionName"})
public record GridCellResponseDto(
	@Schema(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "19422_9582")
	String gridId,

	@Schema(description = "내가 이 격자를 점령(색칠)했는지 여부", example = "true")
	boolean occupied,

	@Schema(description = "이 격자에 올린 내 영상 수 (미점령이면 0)", example = "3")
	Integer videoCount,

	@Schema(description = "격자가 속한 구역 이름. 구역 밖 격자면 null — 이때 표시 이름은 같은 응답의 "
		+ "regionName(행정동)이다(추가 호출 없음).",
		example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\" (행 A는 구역 북단, 열 1은 서단). "
		+ "zoneName 과 항상 쌍이라 구역 밖 격자면 함께 null 이다.",
		example = "I-6", nullable = true)
	String zoneCell,

	@Schema(description = "격자 중심점이 속한 행정동 전체 이름. 아직 아무도 영상을 올리지 않은 격자에도 실린다. "
		+ "어느 행정동에도 속하지 않으면(해상 등) null. zoneName 이 null 이면 이 값이 표시 이름 폴백이다"
		+ "(폴백에는 칸 번호를 붙이지 않는다).",
		example = "부산광역시 영도구 영선1동", nullable = true)
	String regionName
) {

	public static GridCellResponseDto from(GridCellView view) {
		return new GridCellResponseDto(view.gridId(), view.occupied(), view.videoCount(),
			view.zoneName(), view.zoneCell(), view.regionName());
	}
}
