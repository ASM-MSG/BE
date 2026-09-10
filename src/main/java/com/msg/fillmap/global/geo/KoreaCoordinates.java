package com.msg.fillmap.global.geo;

/**
 * 서비스 좌표 범위(한국 근사) — Owner A/B 공용 순수 상수.
 * video 업로드 plausibility 검증(MSG-66 D7)과 region 역지오코딩(MSG-93)이 같은 경계를 공유하며,
 * 서비스 범위가 바뀌면 이 한 곳만 고친다.
 */
public final class KoreaCoordinates {

	public static final double MIN_LAT = 33.0;
	public static final double MAX_LAT = 39.0;
	public static final double MIN_LON = 124.0;
	public static final double MAX_LON = 132.0;

	private KoreaCoordinates() {
	}

	/** 서비스 범위 밖이면 true. NaN 은 범위 비교로 걸러지지 않으므로 명시적으로 밖 처리한다. */
	public static boolean isOutOfService(double lat, double lon) {
		return Double.isNaN(lat) || Double.isNaN(lon)
			|| lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON;
	}
}
