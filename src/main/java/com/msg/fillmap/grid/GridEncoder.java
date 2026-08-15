package com.msg.fillmap.grid;

import static com.msg.fillmap.grid.GridConstants.CELL_SIZE_METERS;
import static com.msg.fillmap.grid.GridConstants.CRS_DEF_EPSG5179;

import java.util.List;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import com.msg.fillmap.grid.dto.ViewportBounds;

/**
 * WGS84 좌표 ↔ 100×100m 격자 변환 유틸.
 * 위경도를 EPSG:5179 미터 평면으로 변환한 뒤 100m 로 나누는 것이 격자 인코딩 규칙이고,
 * 이 클래스와 {@link GridConstants} 가 그 규칙의 단일 진실 원천이다 (MSG-347).
 * 경계는 floor 반열림 구간 [n·100m, (n+1)·100m).
 */
public final class GridEncoder {

	private static final CRSFactory CRS_FACTORY = new CRSFactory();
	private static final CoordinateTransformFactory TRANSFORM_FACTORY = new CoordinateTransformFactory();
	private static final CoordinateReferenceSystem WGS84 =
		CRS_FACTORY.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs");
	private static final CoordinateReferenceSystem KOREA_5179 =
		CRS_FACTORY.createFromParameters("EPSG:5179", CRS_DEF_EPSG5179);

	// 변환 객체는 스레드 간 공유하고 ProjCoordinate 만 호출마다 새로 만든다. Proj4J 1.4.3 소스 실측 근거:
	// BasicCoordinateTransform.transform 은 생성 시 계산해 둔 값만 읽고 쓰기는 인자로 받은 tgt 에만 하며,
	// 투영(TransverseMercatorProjection)도 초기화 이후 필드를 갱신하지 않는다. towgs84 가 전부 0이라
	// datum 이 TYPE_WGS84 로 판정돼(Datum.getTransformType) 격자 파일 지연 로딩 경로도 타지 않는다.
	private static final CoordinateTransform TO_METERS = TRANSFORM_FACTORY.createTransform(WGS84, KOREA_5179);
	private static final CoordinateTransform TO_DEGREES = TRANSFORM_FACTORY.createTransform(KOREA_5179, WGS84);

	private GridEncoder() {
	}

	public static String encode(double lat, double lon) {
		ProjCoordinate meters = toMeters(lat, lon);
		return cellIndex(meters.y) + "_" + cellIndex(meters.x);
	}

	public static GridIndex decode(String gridId) {
		int separator = gridId.indexOf('_');
		long gridY = Long.parseLong(gridId.substring(0, separator));
		long gridX = Long.parseLong(gridId.substring(separator + 1));
		return new GridIndex(gridY, gridX);
	}

	public static GridPoint center(String gridId) {
		GridIndex index = decode(gridId);
		return toDegrees((index.gridX() + 0.5) * CELL_SIZE_METERS, (index.gridY() + 0.5) * CELL_SIZE_METERS);
	}

	/**
	 * 셀 bbox 폴리곤 좌표열 (닫힌 링, 5점: 남서 → 남동 → 북동 → 북서 → 남서).
	 * 5179 셀은 자오선 수렴 때문에 위경도 평면에서 살짝 기울어져 꼭짓점 4점이 모두 다른 값이다 —
	 * 남서/북동 2점으로 사각형을 복원할 수 없어 4점을 각각 역변환한다 (MSG-347).
	 */
	public static List<GridPoint> bbox(String gridId) {
		GridIndex index = decode(gridId);
		double west = index.gridX() * (double) CELL_SIZE_METERS;
		double east = (index.gridX() + 1) * (double) CELL_SIZE_METERS;
		double south = index.gridY() * (double) CELL_SIZE_METERS;
		double north = (index.gridY() + 1) * (double) CELL_SIZE_METERS;
		GridPoint southWest = toDegrees(west, south);
		return List.of(
			southWest,
			toDegrees(east, south),
			toDegrees(east, north),
			toDegrees(west, north),
			southWest
		);
	}

	/**
	 * 뷰포트를 덮는 격자 인덱스 범위 (뷰포트 조회 3곳의 공용 진입점).
	 * 5179 격자 축은 자오선 수렴 때문에 위경도 축과 평행하지 않아 남서·북동 2점만 환산하면 뷰포트
	 * 가장자리 셀이 범위에서 빠진다 — 꼭짓점 4점을 모두 변환해 x·y 각각의 min/max 로 잡는다.
	 * 한국 범위의 횡축 메르카토르는 국소적으로 거의 선형이라 이 범위가 뷰포트를 덮는 보수적 범위다
	 * (화면 밖 셀이 소량 더 잡히는 것은 무해하다, MSG-347).
	 */
	public static GridRange viewportRange(ViewportBounds bounds) {
		ProjCoordinate southWest = toMeters(bounds.swLat(), bounds.swLng());
		ProjCoordinate southEast = toMeters(bounds.swLat(), bounds.neLng());
		ProjCoordinate northEast = toMeters(bounds.neLat(), bounds.neLng());
		ProjCoordinate northWest = toMeters(bounds.neLat(), bounds.swLng());
		return new GridRange(
			cellIndex(min(southWest.y, southEast.y, northEast.y, northWest.y)),
			cellIndex(max(southWest.y, southEast.y, northEast.y, northWest.y)),
			cellIndex(min(southWest.x, southEast.x, northEast.x, northWest.x)),
			cellIndex(max(southWest.x, southEast.x, northEast.x, northWest.x)));
	}

	/**
	 * 점 (lat, lon) 사방 radiusMeters 의 열린 정사각형이 걸치는 셀 인덱스 범위 (radiusMeters > 0).
	 * 반열림 셀 규칙과 정합: 정확히 닿기만 하는 셀은 제외 (상한이 ceil - 1 인 이유).
	 */
	public static GridRange radiusRange(double lat, double lon, double radiusMeters) {
		ProjCoordinate meters = toMeters(lat, lon);
		return new GridRange(
			lowerIndex(meters.y, radiusMeters), upperIndex(meters.y, radiusMeters),
			lowerIndex(meters.x, radiusMeters), upperIndex(meters.x, radiusMeters));
	}

	private static long lowerIndex(double meters, double radius) {
		return (long) Math.floor((meters - radius) / CELL_SIZE_METERS);
	}

	private static long upperIndex(double meters, double radius) {
		return (long) Math.ceil((meters + radius) / CELL_SIZE_METERS) - 1;
	}

	private static double min(double a, double b, double c, double d) {
		return Math.min(Math.min(a, b), Math.min(c, d));
	}

	private static double max(double a, double b, double c, double d) {
		return Math.max(Math.max(a, b), Math.max(c, d));
	}

	private static long cellIndex(double meters) {
		return (long) Math.floor(meters / CELL_SIZE_METERS);
	}

	private static ProjCoordinate toMeters(double lat, double lon) {
		return TO_METERS.transform(new ProjCoordinate(lon, lat), new ProjCoordinate());
	}

	private static GridPoint toDegrees(double x, double y) {
		ProjCoordinate degrees = TO_DEGREES.transform(new ProjCoordinate(x, y), new ProjCoordinate());
		return new GridPoint(degrees.y, degrees.x);
	}

	public record GridIndex(long gridY, long gridX) {
	}

	/** 뷰포트를 덮는 격자 인덱스 범위 (양끝 포함). */
	public record GridRange(long minGridY, long maxGridY, long minGridX, long maxGridX) {
	}

	public record GridPoint(double lat, double lon) {
	}
}
