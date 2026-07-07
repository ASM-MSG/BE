package com.msg.fillmap.grid;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;

import java.util.List;

/**
 * WGS84 좌표 ↔ 100×100m 격자 변환 유틸 (자체 위경도 등간격 양자화).
 * 이 클래스의 상수·수식이 격자 인코딩 규칙의 단일 진실 원천이다.
 * 경계는 floor 반열림 구간 [n·step, (n+1)·step).
 */
public final class GridEncoder {

	private GridEncoder() {
	}

	public static String encode(double lat, double lon) {
		long gridY = (long) Math.floor(lat / GRID_LAT_STEP);
		long gridX = (long) Math.floor(lon / GRID_LNG_STEP);
		return gridY + "_" + gridX;
	}

	public static GridIndex decode(String gridId) {
		int separator = gridId.indexOf('_');
		long gridY = Long.parseLong(gridId.substring(0, separator));
		long gridX = Long.parseLong(gridId.substring(separator + 1));
		return new GridIndex(gridY, gridX);
	}

	public static GridPoint center(String gridId) {
		GridIndex index = decode(gridId);
		double centerLat = (index.gridY() + 0.5) * GRID_LAT_STEP;
		double centerLon = (index.gridX() + 0.5) * GRID_LNG_STEP;
		return new GridPoint(centerLat, centerLon);
	}

	/**
	 * 셀 bbox 폴리곤 좌표열 (닫힌 링, 5점: 남서 → 남동 → 북동 → 북서 → 남서).
	 */
	public static List<GridPoint> bbox(String gridId) {
		GridIndex index = decode(gridId);
		double south = index.gridY() * GRID_LAT_STEP;
		double west = index.gridX() * GRID_LNG_STEP;
		double north = (index.gridY() + 1) * GRID_LAT_STEP;
		double east = (index.gridX() + 1) * GRID_LNG_STEP;
		return List.of(
			new GridPoint(south, west),
			new GridPoint(south, east),
			new GridPoint(north, east),
			new GridPoint(north, west),
			new GridPoint(south, west)
		);
	}

	public record GridIndex(long gridY, long gridX) {
	}

	public record GridPoint(double lat, double lon) {
	}
}
