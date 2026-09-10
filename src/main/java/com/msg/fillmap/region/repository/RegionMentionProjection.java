package com.msg.fillmap.region.repository;

/**
 * 언급 지명 대조 native 조회 결과 프로젝션 (MSG-468). 매칭 그룹 1건 = 1행 — 단위 토큰 이름,
 * 무게중심(WGS84), 외접 사각형(남서·북동), 뷰포트 실경계 겹침. 네이티브 쿼리는 별칭을 getter 와 맞춘다.
 */
public interface RegionMentionProjection {

	String getName();

	double getCenterLat();

	double getCenterLng();

	double getMinLat();

	double getMinLng();

	double getMaxLat();

	double getMaxLng();

	boolean getOverlapsViewport();
}
