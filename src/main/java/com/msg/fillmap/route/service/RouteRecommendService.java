package com.msg.fillmap.route.service;

import com.msg.fillmap.route.dto.RouteRecommendRequestDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto;

/**
 * AI 경로 추천 (MSG-457). 자연어와 뷰포트를 받아 서버 보유 후보(활성 미션·행사·장소 검색 실조회)에서
 * 방문 순서를 붙인 지점 목록을 만든다 — 해석 결과의 문자열은 후보를 만들지 못한다 (FR-ROUTE-03).
 * 전 구간 읽기 전용이라 스탬프·미션 진행 데이터가 변하지 않는다 (FR-ROUTE-09).
 */
public interface RouteRecommendService {

	/** userId 는 요청 제한(FR-ROUTE-12) 키다 — AI 로 나가는 요청에는 실리지 않는다 (NFR-SEC-09). */
	RouteRecommendResponseDto recommend(long userId, RouteRecommendRequestDto request);
}
