package com.msg.fillmap.route.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto;
import com.msg.fillmap.route.dto.RouteWalkPathRequestDto;
import com.msg.fillmap.route.dto.RouteWalkPathResponseDto;
import com.msg.fillmap.route.service.RouteRecommendService;
import com.msg.fillmap.route.service.RouteWalkPathService;

/**
 * AI 경로 추천 API (MSG-457) + 세그먼트 보행 경로 조회 (MSG-483). 3-layer 얇게 — 파싱 + 서비스 호출 +
 * SuccessResponse 변환만. 인증은 SecurityConfig anyRequest 로 강제된다(미인증 401, 비로그인 개방 6종에
 * 미포함 — NFR-SEC-10). 상시 빈이다 — 플래그 꺼짐은 404 가 아니라 14503·14504 이어야 한다 (§설정).
 */
@Tag(name = "AI 경로 추천 (Routes)",
	description = "자연어 한 문장과 뷰포트로 활성 미션·행사·장소 검색 실조회 후보에 방문 순서와 이유를 붙여 돌려준다.")
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

	private final RouteRecommendService routeRecommendService;
	private final RouteWalkPathService routeWalkPathService;

	@Operation(
		summary = "AI 경로 추천",
		description = "자연어 한 문장과 지금 보는 지도 범위를 보내면 서버 보유 후보(활성 미션·행사·장소 검색)에서 "
			+ "골라 방문 순서를 붙인 지점 목록(최대 8개)을 돌려준다. 지점마다 추천 이유 한 줄이 실린다.\n\n"
			+ "후보가 0~2개면 실패가 아니라 찾은 만큼과 notice 안내가 함께 오는 성공이다.\n\n"
			+ "viewport 가 뒤집혔거나 넓이 0 이거나 범위 밖이면 400 + developCode 14400, 한 변이 0.5도를 "
			+ "넘으면 400 + 14401 이다. 같은 사용자의 직전 시도 후 10초 안 재요청은 429 + 14429. AI 해석 "
			+ "실패는 502 + 14502 이고, 기능이 꺼진 환경(route.ai.enabled=false)에서는 503 + 14503 이다."
	)
	@PostMapping("/recommend")
	public SuccessResponse<RouteRecommendResponseDto> recommend(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody RouteRecommendRequestDto request
	) {
		return SuccessResponse.of(routeRecommendService.recommend(principal.userId(), request));
	}

	@Operation(
		summary = "세그먼트 보행 경로 조회",
		description = "추천 응답의 이웃 좌표쌍(출발지 구간 포함 1~8개)을 보내면 서버가 TMap 보행자 경로안내를 대신 "
			+ "호출해 세그먼트별 보행 좌표열과 실거리(미터)를 요청과 같은 개수, 같은 순서로 돌려준다.\n\n"
			+ "TMap 호출 실패·형태 위반·일 한도 소진은 에러가 아니라 200 에 해당 세그먼트 resolved: false 다 — "
			+ "그 세그먼트는 직선과 직선거리 안내를 유지하면 된다 (부분 실패 허용).\n\n"
			+ "목록이 없거나 비었거나 9개 이상, 원소가 null, 좌표가 한국 서비스 범위(위도 33~39·경도 124~132) "
			+ "밖이면 400 + developCode 14402 이고, 기능이 꺼진 환경(route.walk.enabled=false)에서는 503 + 14504 다."
	)
	@PostMapping("/walk-paths")
	public SuccessResponse<RouteWalkPathResponseDto> walkPaths(@RequestBody RouteWalkPathRequestDto request) {
		return SuccessResponse.of(routeWalkPathService.walkPaths(request));
	}
}
