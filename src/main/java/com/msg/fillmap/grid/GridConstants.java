package com.msg.fillmap.grid;

/**
 * 격자 계산 규칙 상수 (glossary "격자 계산 규칙").
 * FE·BE·모바일이 공유하는 단일 상수 — 값을 바꾸면 전 격자 매핑이 바뀌므로 임의 변경 금지.
 */
public final class GridConstants {

	/** 셀 한 변 길이(m). EPSG:5179 는 미터 평면이라 전국 어디서나 정확히 100m 다 (MSG-347). */
	public static final int CELL_SIZE_METERS = 100;

	/**
	 * EPSG:5179(한국 국가 표준 평면 좌표계) proj4 정의 문자열.
	 * BE(Proj4J)·FE(proj4js)·이행 마이그레이션 SQL(ST_Transform)이 글자 단위로 공유하는 계약이며
	 * 좌표 변환 정의의 단일 진실 원천이다 — 한 글자만 달라도 경계 근처 셀이 서로 어긋난다 (MSG-347).
	 */
	public static final String CRS_DEF_EPSG5179 =
		"+proj=tmerc +lat_0=38 +lon_0=127.5 +k=0.9996 +x_0=1000000 +y_0=2000000 +ellps=GRS80 "
			+ "+towgs84=0,0,0,0,0,0,0 +units=m +no_defs";

	private GridConstants() {
	}
}
