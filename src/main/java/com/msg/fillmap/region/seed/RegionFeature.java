package com.msg.fillmap.region.seed;

/**
 * GeoJSON feature 한 건에서 추출한 시딩 입력. geometryJson 은 ST_GeomFromGeoJSON 에
 * 그대로 바인딩되는 geometry 노드의 JSON 문자열이다.
 */
public record RegionFeature(String regionCode, String regionName, String parentCode, String geometryJson) {
}
