package com.msg.fillmap.video.repository;

/**
 * 행정동 헤더 카운트 프로젝션 (단일 네이티브 쿼리 결과, MSG-238 §도메인 2). "이 지역 격자 N개 · 영상 M개"의
 * 정본 — 게이트·귀속이 카드 쿼리와 동일해 limit 무관하게 카드 집합과 항상 일치한다(§D1). 실존 region 은
 * 콘텐츠 0 이어도 1행(LEFT JOIN 0 합성), 미존재 region 은 0행 — 서비스가 regionName null 로 합성한다(§D2).
 */
public interface RegionExploreSummaryProjection {

	String getRegionName();

	/** 게이트 통과 영상 ≥1 격자 수 — 카드 집합 크기와 동일 정의(§D1). */
	Integer getGridCount();

	/** 게이트 통과 영상 총수 — 카드 videoCount 합과 동일 정의(§D1). */
	Long getVideoCount();
}
