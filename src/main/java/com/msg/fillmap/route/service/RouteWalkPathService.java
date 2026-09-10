package com.msg.fillmap.route.service;

import com.msg.fillmap.route.dto.RouteWalkPathRequestDto;
import com.msg.fillmap.route.dto.RouteWalkPathResponseDto;

/**
 * 세그먼트 보행 경로 조회 (MSG-483). 추천 결과의 이웃 좌표쌍을 TMap 보행자 경로안내로 풀어 세그먼트별
 * 보행 좌표열과 실거리를 요청과 같은 개수·순서로 돌려준다. 실패 세그먼트는 에러가 아니라
 * {@code resolved: false} 다 — 추천 기능과 나머지 세그먼트에 영향이 없다 (FR-ROUTE-17).
 */
public interface RouteWalkPathService {

	RouteWalkPathResponseDto walkPaths(RouteWalkPathRequestDto request);
}
