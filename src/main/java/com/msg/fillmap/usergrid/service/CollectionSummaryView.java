package com.msg.fillmap.usergrid.service;

/**
 * 도감 요약 내부 뷰 (서비스 간 계약, MSG-152). HTTP 응답 DTO 로의 변환은 컨트롤러가 한다(GridCellView 대칭).
 */
public record CollectionSummaryView(int totalGridCount, long totalVideoCount, int visitedRegionCount) {
}
