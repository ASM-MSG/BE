package com.msg.fillmap.usergrid.service;

import java.util.List;

/**
 * 개인 도감 조회 계약 (B 제공 → A 소비, infrastructure.md 경계면). GridQueryService 와 대칭.
 * 엔티티는 grid.entity.UserGrid(Owner A, MSG-78 D6) — usergrid 는 그 위의 조회 계약 패키지.
 */
public interface UserGridQueryService {

	/** 로그인 사용자의 도감 요약: 점령 격자 수 · 영상 총합 · 방문 행정동 수. 점령 0건이면 (0, 0, 0). */
	CollectionSummaryView getCollectionSummary(long userId);

	/**
	 * 갤러리 격자 목록: 내 점령 격자를 최근 수집순(first_collected_at DESC) 최대 30개 (MSG-153, B 내부 read).
	 * 점령 0건이면 빈 리스트. cover 가 없거나 READY 이전이면 coverVideoId·coverThumbnailUrl 이 null.
	 */
	List<CollectionGridView> getCollectionGrids(long userId);
}
