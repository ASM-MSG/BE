package com.msg.fillmap.grid.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.grid.service.OccupiedGridView;

/**
 * viewport 색칠 항목 (최소 필드). gridId + FE 렌더링용 정수 인덱스(gridY/gridX)만 담는다.
 * coverVideo 등 영상 확장은 MSG-90 소관 — 여기선 셀 위치만.
 */
@Schema(description = "뷰포트 색칠 격자 한 칸 — 지도 렌더링용 위치 정보",
	requiredProperties = {"gridId", "gridY", "gridX", "zoneName", "zoneCell"})
public record OccupiedGridResponseDto(
	@Schema(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "19422_9582")
	String gridId,

	@Schema(description = "격자 세로 인덱스 (EPSG:5179 평면 y / 100 — 위도가 아니다)", example = "19422")
	int gridY,

	@Schema(description = "격자 가로 인덱스 (EPSG:5179 평면 x / 100 — 경도가 아니다)", example = "9582")
	int gridX,

	@Schema(description = "격자가 속한 구역 이름. 구역 밖 격자면 null — 지도 오버레이는 라벨을 그리지 않으므로 "
		+ "행정동 폴백 재료를 싣지 않는다(셀을 누르면 단일 격자 조회가 라벨을 준다).",
		example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\" (행 A는 구역 북단, 열 1은 서단) — 셀 배지용. "
		+ "zoneName 과 항상 쌍이라 구역 밖 격자면 함께 null 이다.",
		example = "I-6", nullable = true)
	String zoneCell
) {

	public static OccupiedGridResponseDto from(OccupiedGridView view) {
		return new OccupiedGridResponseDto(view.gridId(), view.gridY(), view.gridX(),
			view.zoneName(), view.zoneCell());
	}
}
