package com.msg.fillmap.hotzone.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.hotzone.service.HotZoneView;

/**
 * 핫구역 항목 — 격자 위치 + 핫스코어. user_id 는 싣지 않는다 (PRD 보안 비기능).
 */
@Schema(description = "핫구역 한 칸 — 최근 48시간 방문(업로드) 신호가 상위인 격자",
	requiredProperties = {"gridId", "gridY", "gridX", "score", "zoneName", "zoneCell"})
public record HotZoneResponseDto(
	@Schema(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "41642_110458")
	String gridId,

	@Schema(description = "격자 세로 인덱스 (위도 기반 정수)", example = "41642")
	int gridY,

	@Schema(description = "격자 가로 인덱스 (경도 기반 정수)", example = "110458")
	int gridX,

	@Schema(description = "핫스코어 — 최근 48시간(8버킷) 방문 신호 합산", example = "12")
	long score,

	@Schema(description = "격자가 속한 구역 이름. 구역 밖 격자면 null — 지도 마커는 라벨을 그리지 않으므로 "
		+ "행정동 폴백 재료를 싣지 않는다(마커를 누르면 단일 격자 조회가 라벨을 준다).",
		example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\" (행 A는 구역 북단, 열 1은 서단) — 마커 배지용. "
		+ "zoneName 과 항상 쌍이라 구역 밖 격자면 함께 null 이다.",
		example = "I-6", nullable = true)
	String zoneCell
) {

	public static HotZoneResponseDto from(HotZoneView view) {
		return new HotZoneResponseDto(view.gridId(), view.gridY(), view.gridX(), view.score(),
			view.zoneName(), view.zoneCell());
	}
}
