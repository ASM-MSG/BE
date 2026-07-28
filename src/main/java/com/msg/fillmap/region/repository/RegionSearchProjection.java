package com.msg.fillmap.region.repository;

/**
 * 지역 검색 폴백 결과 프로젝션 (MSG-234 §D6). zones.name 무매치일 때만 도는 regions 이름 검색의
 * 행별 결과 — region_name·코드 + ST_Envelope 로 뽑은 bbox(minLat/minLon/maxLat/maxLon)를 담는다.
 * 네이티브 쿼리는 별칭을 이 getter 이름에 맞춘다.
 */
public interface RegionSearchProjection {

	String getRegionCode();

	String getRegionName();

	String getParentCode();

	Double getMinLat();

	Double getMinLon();

	Double getMaxLat();

	Double getMaxLon();
}
