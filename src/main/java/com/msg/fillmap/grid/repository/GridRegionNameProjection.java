package com.msg.fillmap.grid.repository;

/**
 * 격자 → 행정동 이름 일괄 조회 결과 프로젝션 (MSG-349). 이름 사전을 만들기 위한 두 컬럼만 노출한다.
 * JPQL 의 SELECT 별칭을 gridId/regionName 으로 맞춘다 (MSG-585 전환 전에는 네이티브 컬럼 별칭).
 */
public interface GridRegionNameProjection {

	String getGridId();

	String getRegionName();
}
