package com.msg.fillmap.usergrid.repository;

/**
 * 도감 요약 집계 프로젝션 (단일 네이티브 쿼리 결과, MSG-152). 네이티브 쿼리는 컬럼 별칭을
 * totalGridCount/totalVideoCount/visitedRegionCount 로 맞춘다.
 */
public interface CollectionSummaryProjection {

	Integer getTotalGridCount();

	Long getTotalVideoCount();

	Integer getVisitedRegionCount();
}
