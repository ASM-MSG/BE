package com.msg.fillmap.search.service;

import java.util.List;

import com.msg.fillmap.search.dto.PlaceSearchResponseDto;

/**
 * 장소 검색 계약 (MSG-251, Owner A). 카카오 로컬 키워드 검색 실시간 패스스루 + 격자 ID 즉석 합성 —
 * 카카오 응답 무저장이 약관 준수의 실체이고, 사용자가 입력한 검색어(q) 집계는 경계 안이다
 * (MSG-258, 데브톡 150397). searcherKey 는 검색어 집계 dedupe(검색자·검색어당 일 1회) 전용이며 검색 결과를
 * 개인화하지 않는다. 1인자 오버로드는 route(Owner B)가 소비하는 크로스오너 계약 경계면이다(MSG-457).
 */
public interface PlaceSearchService {

	/**
	 * 장소명 자유 텍스트 검색. trim 후 빈 q 는 카카오 호출 없이 빈 리스트(§D3 trim 가드), 무매치도 빈 리스트.
	 * 카카오 실패(5xx·타임아웃·4xx·파싱)는 SearchErrorCode.SEARCH_UPSTREAM_ERROR(5502) 로 수렴한다.
	 * 유효한 검색어는 카카오 호출 전에 집계로 접수된다 — 집계 실패는 검색 결과에 영향을 주지 않는다(MSG-258 FR-1·6).
	 *
	 * @param searcherKey 집계 dedupe 기준. 로그인은 사용자 id 의 문자열 표현, 비로그인은 {@code s:} 를 붙인
	 *                    방문자 세션 값이다. null 이면 집계만 생략하고 검색은 그대로 수행한다 (MSG-469 D3)
	 */
	List<PlaceSearchResponseDto> searchPlaces(String searcherKey, String q);

	/**
	 * 집계 없는 장소명 검색 (MSG-457 계약 변경). 경로 추천이 기계 조립한 검색어 전용 — 사용자가 입력창에 친
	 * 검색어가 아니므로 검색어 집계(인기 검색어 재료, MSG-258)에 접수하지 않는다. trim 가드·카카오 호출·검증·
	 * 매핑 등 결과 규칙은 {@link #searchPlaces(String, String)} 와 동일하다.
	 */
	List<PlaceSearchResponseDto> searchPlaces(String q);
}
