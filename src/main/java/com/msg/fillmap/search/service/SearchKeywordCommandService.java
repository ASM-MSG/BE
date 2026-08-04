package com.msg.fillmap.search.service;

/**
 * 검색어 집계 계약 (MSG-258, Owner A). 검색 1건을 일별 카운트에 반영한다 — search 패키지 내부 3-layer
 * 경계이며 크로스오너 계약이 아니다.
 */
public interface SearchKeywordCommandService {

	/**
	 * 검색어 집계 접수. 정규화(trim·연속 공백 1칸·소문자) 후 사용자·검색어·날짜(KST) 단위로 일 1회만
	 * 카운트한다. fire-and-forget — 호출 즉시 반환하고 저장소 장애는 삼킨다(신호 1건 유실 허용, FR-6).
	 */
	void recordSearch(long userId, String rawQuery);
}
