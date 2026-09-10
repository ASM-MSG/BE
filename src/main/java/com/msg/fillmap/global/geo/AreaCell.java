package com.msg.fillmap.global.geo;

/**
 * 행사 위치 영역을 이루는 격자 한 칸의 인덱스 (MSG-438). 대표 격자 결정이 인덱스 정수 산술만으로 끝나므로
 * 위경도나 투영이 필요 없다 — EPSG:5179 인덱스 공간은 미터 평면의 균일 축소라 인덱스 거리 비교가 실거리와 동치다.
 * <p>
 * 행사 위치의 영역만 담는 타입이 아니다 — MSG-459 부터 축제·팝업 미션의 판정 격자 집합도 같은 산출기에
 * 이 타입으로 들어간다. "영역을 이루는 칸 하나"라는 뜻만 가지며 어느 도메인의 영역인지는 호출자가 안다.
 */
public record AreaCell(int gridY, int gridX) {

	public String gridId() {
		return gridY + "_" + gridX;
	}
}
