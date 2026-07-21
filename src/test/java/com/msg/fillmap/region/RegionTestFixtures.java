package com.msg.fillmap.region;

/**
 * region 테스트 공용 fixture. 공유 로컬 DB 오염 방지를 위해 실존하지 않는 sido 코드 999 대역의
 * 합성 region_code 만 쓴다(실데이터 3,558건과 절대 충돌하지 않음). 실제 정리는 @Transactional 롤백.
 */
public final class RegionTestFixtures {

	/** 명목 셀 면적(D1) — total_grid_count = ROUND(면적/CELL_AREA_M2). */
	public static final long CELL_AREA_M2 = 10_000L;

	/** 실존하지 않는 sido(99) 대역 합성 코드 접두어. */
	public static final String SYNTHETIC_PREFIX = "999";

	private RegionTestFixtures() {
	}

	/** 합성 region_code (10자리, 999 대역). suffix 로 유일성 확보. */
	public static String syntheticCode(String suffix) {
		return SYNTHETIC_PREFIX + String.format("%07d", Integer.parseInt(suffix));
	}

	/**
	 * 위경도 직사각형을 닫힌 링 Polygon GeoJSON 으로 만든다(MultiPolygon 아님 — ST_Multi 정규화 검증용).
	 * 링 순서: 남서 → 남동 → 북동 → 북서 → 남서.
	 */
	public static String rectanglePolygonJson(double minLon, double minLat, double maxLon, double maxLat) {
		return ("{\"type\":\"Polygon\",\"coordinates\":[["
			+ "[%1$s,%2$s],[%3$s,%2$s],[%3$s,%4$s],[%1$s,%4$s],[%1$s,%2$s]"
			+ "]]}").formatted(minLon, minLat, maxLon, maxLat);
	}

	/**
	 * 서울(위도 ≈37.5) 기준 지정 면적(km²)에 근사한 직사각형(정사각형) Polygon GeoJSON.
	 * 위도 1km ≈ 0.009013°, 경도 1km ≈ 0.011323°(cos37.5°) 로 환산한다.
	 */
	public static String squareKm2Json(double areaKm2) {
		double sideKm = Math.sqrt(areaKm2);
		double halfLatDeg = sideKm * 0.009013 / 2.0;
		double halfLonDeg = sideKm * 0.011323 / 2.0;
		double centerLat = 37.5;
		double centerLon = 127.0;
		return rectanglePolygonJson(
			centerLon - halfLonDeg, centerLat - halfLatDeg,
			centerLon + halfLonDeg, centerLat + halfLatDeg);
	}
}
