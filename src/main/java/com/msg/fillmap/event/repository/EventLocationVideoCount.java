package com.msg.fillmap.event.repository;

/**
 * 위치별 영상 수 집계 한 행 (MSG-439 API 3). GROUP BY 결과라 videoCount 는 항상 1 이상이고,
 * 영상이 없는 위치는 행 자체가 없다 — 0 채움은 호출자(EventQueryServiceImpl) 몫이다.
 */
public record EventLocationVideoCount(Long locationId, Long videoCount) {
}
