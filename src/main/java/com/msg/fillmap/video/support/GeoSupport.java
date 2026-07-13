package com.msg.fillmap.video.support;

import java.util.StringJoiner;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;

/**
 * 좌표 → PostGIS geometry 변환 유틸. WGS84(SRID 4326).
 * WKT/JTS 는 (X=경도, Y=위도) 순서 — lat/lon 뒤바뀌지 않도록 여기서만 변환한다.
 */
public final class GeoSupport {

	private static final int SRID_WGS84 = 4326;
	private static final GeometryFactory GEOMETRY_FACTORY =
		new GeometryFactory(new PrecisionModel(), SRID_WGS84);

	private GeoSupport() {
	}

	public static Point toPoint(double lat, double lon) {
		return GEOMETRY_FACTORY.createPoint(new Coordinate(lon, lat));
	}

	/**
	 * 격자 bbox 폴리곤 WKT. GridEncoder 가 준 닫힌 링(5점)을 "lon lat" 순서로 직렬화한다.
	 */
	public static String bboxWkt(String gridId) {
		StringJoiner vertices = new StringJoiner(", ", "POLYGON((", "))");
		for (GridPoint p : GridEncoder.bbox(gridId)) {
			vertices.add(p.lon() + " " + p.lat());
		}
		return vertices.toString();
	}
}
