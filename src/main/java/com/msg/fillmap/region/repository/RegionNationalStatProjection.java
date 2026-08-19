package com.msg.fillmap.region.repository;

/**
 * 전국 탐험률 재료(분자/분모) 네이티브 조회 결과 프로젝션 (MSG-406). RegionStatProjection(MSG-156) 선례대로
 * 인터페이스 프로젝션이라 엔티티를 만들지 않는다. 두 값 모두 SUM 결과라 bigint — Long 으로 받는다.
 * 반올림도 100 clamp 도 하지 않은 원값이다(§D-2·D-3).
 */
public interface RegionNationalStatProjection {

	/** 내가 점령한 격자 수의 전국 합 (region_stats.collected_count 합). 수집이 없으면 0. */
	Long getCollectedCount();

	/** 전국 격자 총수 (regions.total_grid_count 합). regions 미적재면 0. */
	Long getTotalCount();
}
