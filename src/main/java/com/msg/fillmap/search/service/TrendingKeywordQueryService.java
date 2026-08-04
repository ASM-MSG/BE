package com.msg.fillmap.search.service;

import java.util.List;

import com.msg.fillmap.search.dto.TrendingKeywordResponseDto;

/**
 * 인기 검색어 조회 계약 (MSG-258, Owner A). 전역 집계라 사용자별 개인화가 없다 —
 * search 패키지 내부 3-layer 경계이며 크로스오너 계약이 아니다.
 */
public interface TrendingKeywordQueryService {

	/**
	 * 오늘+어제(KST) 합산 상위 10개. 동률은 검색어 사전순이고, 집계가 없으면 예외가 아니라 빈 목록이다(FR-5).
	 * 10개 미만이면 있는 만큼만 반환한다.
	 */
	List<TrendingKeywordResponseDto> findTop10();
}
