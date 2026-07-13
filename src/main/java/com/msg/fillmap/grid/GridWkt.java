package com.msg.fillmap.grid;

import java.util.StringJoiner;

/**
 * GridEncoder 좌표(위도, 경도)를 PostGIS 축 순서(경도 위도)의 WKT로 변환하는 어댑터.
 * 두 규약의 축 순서가 서로 반대이므로 변환을 이 클래스 한 곳에 격리한다 (MSG-68 §2).
 */
public final class GridWkt {

	private GridWkt() {
	}

	/**
	 * 셀 bbox 닫힌 링 5점(남서→남동→북동→북서→남서)을 {@code POLYGON((lon lat, ...))} WKT로 조립한다.
	 */
	public static String bboxPolygon(String gridId) {
		StringJoiner ring = new StringJoiner(", ", "POLYGON((", "))");
		for (GridEncoder.GridPoint point : GridEncoder.bbox(gridId)) {
			ring.add(point.lon() + " " + point.lat());
		}
		return ring.toString();
	}
}
