package com.msg.fillmap.hotzone.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.hotzone.service.HotZoneView;

/**
 * 핫구역 항목 — 격자 위치 + 핫스코어. user_id 는 싣지 않는다 (PRD 보안 비기능).
 */
@Schema(description = "핫구역 한 칸 — 최근 48시간 방문(업로드) 신호가 상위인 격자")
public record HotZoneResponseDto(
	@Schema(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "41642_110458")
	String gridId,

	@Schema(description = "격자 세로 인덱스 (위도 기반 정수)", example = "41642")
	int gridY,

	@Schema(description = "격자 가로 인덱스 (경도 기반 정수)", example = "110458")
	int gridX,

	@Schema(description = "핫스코어 — 최근 48시간(8버킷) 방문 신호 합산", example = "12")
	long score
) {

	public static HotZoneResponseDto from(HotZoneView view) {
		return new HotZoneResponseDto(view.gridId(), view.gridY(), view.gridX(), view.score());
	}
}
