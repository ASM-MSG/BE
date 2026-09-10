package com.msg.fillmap.grid.repository;

/**
 * 뷰포트 집계 네이티브 결과 프로젝션 (MSG-356). 네이티브 쿼리는 컬럼 별칭을 각 getter 프로퍼티명으로 맞춘다.
 * 미판정 묶음 행은 regionCode·name 이 null 이다. lat/lng 는 AVG(double precision), count 는 COUNT(*)(bigint)라
 * 각각 Double·Long 으로 받고, 서비스가 뷰 타입(double·int)으로 좁힌다.
 */
public interface RegionAggregateProjection {

	String getRegionCode();

	String getName();

	Double getLat();

	Double getLng();

	Long getCount();
}
