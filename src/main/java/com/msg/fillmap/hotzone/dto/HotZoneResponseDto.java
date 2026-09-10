package com.msg.fillmap.hotzone.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.hotzone.service.HotZoneView;

/**
 * 핫구역 항목 — 격자 위치 + 핫스코어. user_id 는 싣지 않는다 (PRD 보안 비기능).
 */
@Schema(description = "핫구역 한 칸 — 최근 48시간 방문(업로드) 신호가 상위인 격자",
	requiredProperties = {"gridId", "gridY", "gridX", "score", "zoneName", "zoneCell", "regionName"})
public record HotZoneResponseDto(
	@Schema(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "19422_9582")
	String gridId,

	@Schema(description = "격자 세로 인덱스 (EPSG:5179 평면 y / 100 — 위도가 아니다)", example = "19422")
	int gridY,

	@Schema(description = "격자 가로 인덱스 (EPSG:5179 평면 x / 100 — 경도가 아니다)", example = "9582")
	int gridX,

	@Schema(description = "핫스코어 — 최근 48시간(8버킷) 방문 신호 합산", example = "12")
	long score,

	@Schema(description = "격자가 속한 구역 이름. 구역 밖 격자면 null — 이때 마커 라벨은 같은 항목의 "
		+ "regionName(행정동)이다(추가 호출 없음).",
		example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\" (행 A는 구역 북단, 열 1은 서단) — 마커 배지용. "
		+ "zoneName 과 항상 쌍이라 구역 밖 격자면 함께 null 이다.",
		example = "I-6", nullable = true)
	String zoneCell,

	@Schema(description = "격자 중심점이 속한 행정동 전체 이름. 어느 행정동에도 속하지 않으면(해상 등) null. "
		+ "zoneName 이 null 이면 이 값이 표시 이름 폴백이다(폴백에는 칸 번호를 붙이지 않는다).",
		example = "부산광역시 부산진구 부전1동", nullable = true)
	String regionName
) {

	public static HotZoneResponseDto from(HotZoneView view) {
		return new HotZoneResponseDto(view.gridId(), view.gridY(), view.gridX(), view.score(),
			view.zoneName(), view.zoneCell(), view.regionName());
	}
}
