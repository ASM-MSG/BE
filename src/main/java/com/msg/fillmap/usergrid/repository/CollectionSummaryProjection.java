package com.msg.fillmap.usergrid.repository;

/**
 * 도감 요약 집계 프로젝션 (단일 네이티브 쿼리 결과, MSG-152). 네이티브 쿼리는 컬럼 별칭을
 * totalGridCount/totalVideoCount/visitedRegionCount 로 맞춘다. currentStreak/maxStreak/badgeCount 는
 * MSG-362 확장 — 같은 문장의 스칼라 서브쿼리 3개가 채운다(행 없는 사용자는 쿼리 COALESCE 가 0 보장).
 */
public interface CollectionSummaryProjection {

	Integer getTotalGridCount();

	Long getTotalVideoCount();

	Integer getVisitedRegionCount();

	Integer getCurrentStreak();

	Integer getMaxStreak();

	Integer getBadgeCount();
}
