package com.msg.fillmap.grid.repository;

/**
 * 격자 → 행정동 코드·이름 일괄 조회 결과 프로젝션 (MSG-466). 접두 그룹핑에 코드 원값(10자리)이 필요해
 * 이름만 주는 GridRegionNameProjection 과 달리 코드를 함께 싣는다.
 * JPQL 의 SELECT 별칭을 게터 이름(gridId/regionCode/regionName)과 맞춘다.
 */
public interface GridRegionCodeNameProjection {

	String getGridId();

	String getRegionCode();

	String getRegionName();
}
