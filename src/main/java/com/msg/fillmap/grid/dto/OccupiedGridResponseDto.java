package com.msg.fillmap.grid.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.grid.service.OccupiedGridView;

/**
 * viewport 색칠 항목 (최소 필드). gridId + FE 렌더링용 정수 인덱스(gridY/gridX)만 담는다.
 * coverVideo 등 영상 확장은 MSG-90 소관 — 여기선 셀 위치만.
 */
@Schema(description = "뷰포트 색칠 격자 한 칸 — 지도 렌더링용 위치 정보",
	requiredProperties = {"gridId", "gridY", "gridX", "zoneName", "zoneCell", "regionName"})
public record OccupiedGridResponseDto(
	@Schema(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "19422_9582")
	String gridId,

	@Schema(description = "격자 세로 인덱스 (EPSG:5179 평면 y / 100 — 위도가 아니다)", example = "19422")
	int gridY,

	@Schema(description = "격자 가로 인덱스 (EPSG:5179 평면 x / 100 — 경도가 아니다)", example = "9582")
	int gridX,

	@Schema(description = "격자가 속한 구역 이름. 구역 밖 격자면 null — 이때 표시 이름은 같은 항목의 "
		+ "regionName(행정동)이다(추가 호출 없음).",
		example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\" (행 A는 구역 북단, 열 1은 서단) — 셀 배지용. "
		+ "zoneName 과 항상 쌍이라 구역 밖 격자면 함께 null 이다.",
		example = "I-6", nullable = true)
	String zoneCell,

	@Schema(description = "격자 중심점이 속한 행정동 전체 이름. 어느 행정동에도 속하지 않으면(해상 등) null. "
		+ "zoneName 이 null 이면 이 값이 표시 이름 폴백이다(폴백에는 칸 번호를 붙이지 않는다).",
		example = "부산광역시 부산진구 부전1동", nullable = true)
	String regionName
) {

	public static OccupiedGridResponseDto from(OccupiedGridView view) {
		return new OccupiedGridResponseDto(view.gridId(), view.gridY(), view.gridX(),
			view.zoneName(), view.zoneCell(), view.regionName());
	}
}
