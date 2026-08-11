package com.msg.fillmap.usergrid.service;

/**
 * 도감 요약 내부 뷰 (서비스 간 계약, MSG-152). HTTP 응답 DTO 로의 변환은 컨트롤러가 한다(GridCellView 대칭).
 * currentStreak/maxStreak/badgeCount 는 MSG-362 확장 — currentStreak 은 쿼리가 조회 시점 유효성을 판정한
 * 값이라 끊긴 스트릭이면 0 이고(§D3), maxStreak 은 끊겨도 유지, badgeCount 는 획득 뱃지 수다.
 */
public record CollectionSummaryView(
	int totalGridCount,
	long totalVideoCount,
	int visitedRegionCount,
	int currentStreak,
	int maxStreak,
	int badgeCount
) {
}
