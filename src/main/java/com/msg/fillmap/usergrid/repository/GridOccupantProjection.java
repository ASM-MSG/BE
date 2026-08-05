package com.msg.fillmap.usergrid.repository;

/**
 * 격자 점령 사용자 역조회 프로젝션 (단일 네이티브 쿼리 결과, MSG-181 D6). region_name 이 행마다
 * 중복되지만 1왕복이 우선 — 서비스가 GridOccupantView 로 옮긴다.
 */
public interface GridOccupantProjection {

	Long getUserId();

	/** 격자 중심점 행정동 이름(grids.region_code 경유). 무귀속(해상 등)이면 null. */
	String getRegionName();
}
