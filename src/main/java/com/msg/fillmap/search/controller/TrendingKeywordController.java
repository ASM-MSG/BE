package com.msg.fillmap.search.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.search.dto.TrendingKeywordResponseDto;
import com.msg.fillmap.search.service.TrendingKeywordQueryService;

/**
 * 인기 검색어 순위 API (MSG-258). 3-layer 얇게 — 서비스 호출 + SuccessResponse 변환만.
 * 전역 집계라 @AuthenticationPrincipal 미사용(개인화 없음)이고, 응답이 사용자 무관이라 비로그인 열람을
 * 허용한다(SecurityConfig permitAll, MSG-469). 요청 파라미터가 없어 신규 에러 코드도 없다(§D7).
 */
@Tag(name = "인기 검색어 (Trending)", description = "사용자 검색어 일별 집계 기반 인기 검색어 순위 — 오늘+어제 합산 TOP 10.")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class TrendingKeywordController {

	private final TrendingKeywordQueryService trendingKeywordQueryService;

	@Operation(
		summary = "인기 검색어 TOP 10",
		description = "오늘+어제(KST) 검색어 집계를 합산해 상위 10개를 순위·검색어로 반환한다. 동률은 검색어 사전순. "
			+ "검색 횟수와 장소 정보는 포함하지 않으며(클릭 후 장소 검색 API 재호출), 집계가 없으면 200 + 빈 배열이다."
	)
	@GetMapping("/trending")
	public SuccessResponse<List<TrendingKeywordResponseDto>> getTrendingKeywords() {
		return SuccessResponse.of(trendingKeywordQueryService.findTop10());
	}
}
