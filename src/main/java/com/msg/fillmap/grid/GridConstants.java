package com.msg.fillmap.grid;

/**
 * 격자 계산 규칙 상수 (glossary "격자 계산 규칙").
 * FE·BE·모바일이 공유하는 단일 상수 — 값을 바꾸면 전 격자 매핑이 바뀌므로 임의 변경 금지.
 */
public final class GridConstants {

	public static final double GRID_LAT_STEP = 0.0009;
	public static final double GRID_LNG_STEP = 0.00115;

	private GridConstants() {
	}
}
