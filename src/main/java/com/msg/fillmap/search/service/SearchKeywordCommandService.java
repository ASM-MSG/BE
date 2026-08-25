package com.msg.fillmap.search.service;

/**
 * 검색어 집계 계약 (MSG-258, Owner A). 검색 1건을 일별 카운트에 반영한다 — search 패키지 내부 3-layer
 * 경계이며 크로스오너 계약이 아니다.
 */
public interface SearchKeywordCommandService {

	/**
	 * 검색어 집계 접수. 정규화(trim·연속 공백 1칸·소문자) 후 검색자·검색어·날짜(KST) 단위로 일 1회만
	 * 카운트한다. fire-and-forget — 호출 즉시 반환하고 저장소 장애는 삼킨다(신호 1건 유실 허용, FR-6).
	 *
	 * @param searcherKey 로그인은 사용자 id 의 문자열 표현, 비로그인은 {@code s:} 를 붙인 방문자 세션 값이다.
	 *                    둘은 첫 글자로 갈려(십진수 vs s) member 공간이 겹치지 않는다 (MSG-469 D4).
	 *                    null 을 넘기지 않는다 — 집계 대상이 아닌 요청은 호출부가 접수 자체를 건너뛴다
	 */
	void recordSearch(String searcherKey, String rawQuery);
}
