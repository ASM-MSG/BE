package com.msg.fillmap.usergrid.service;

/**
 * 개인 도감 조회 계약 (B 제공 → A 소비, infrastructure.md 경계면). GridQueryService 와 대칭.
 * 엔티티는 grid.entity.UserGrid(Owner A, MSG-78 D6) — usergrid 는 그 위의 조회 계약 패키지.
 */
public interface UserGridQueryService {

	/** 로그인 사용자의 도감 요약: 점령 격자 수 · 영상 총합 · 방문 행정동 수. 점령 0건이면 (0, 0, 0). */
	CollectionSummaryView getCollectionSummary(long userId);
}
