package com.msg.fillmap.grid.repository;

/**
 * 격자 → 행정동 이름 일괄 조회 결과 프로젝션 (MSG-349). 이름 사전을 만들기 위한 두 컬럼만 노출한다.
 * 네이티브 쿼리는 컬럼 별칭을 gridId/regionName 으로 맞춘다.
 */
public interface GridRegionNameProjection {

	String getGridId();

	String getRegionName();
}
