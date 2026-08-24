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
import com.msg.fillmap.route.service.RouteRecommendService;

/**
 * AI 경로 추천 API (MSG-457). 3-layer 얇게 — 파싱 + 서비스 호출 + SuccessResponse 변환만. 인증은
 * SecurityConfig anyRequest 로 강제된다(미인증 401) — 요청 제한(FR-ROUTE-12)에 로그인 사용자 id 를
 * 쓰므로 비로그인 조회가 없다. 상시 빈이다 — 플래그 꺼짐은 404 가 아니라 14503 이어야 한다 (§설정).
 */
@Tag(name = "AI 경로 추천 (Routes)",
	description = "자연어 한 문장과 뷰포트로 활성 미션·행사·장소 검색 실조회 후보에 방문 순서와 이유를 붙여 돌려준다.")
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

	private final RouteRecommendService routeRecommendService;

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
}
