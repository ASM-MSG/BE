package com.msg.fillmap.search.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.search.dto.PlaceSearchResponseDto;
import com.msg.fillmap.search.service.PlaceSearchService;

/**
 * 장소 검색 API (MSG-251 · 카카오 로컬 프록시). 3-layer 얇게 — 파싱 + 서비스 호출 + SuccessResponse 변환만.
 * 개인 데이터가 아니라 @AuthenticationPrincipal 미사용(238 선례), 인증은 SecurityConfig anyRequest 로
 * 강제된다(미인증 401 — SecurityConfig 무변경). q 누락 400 은 global 핸들러 소관이라 신규 코드가 없다(§D3).
 */
@Tag(name = "장소 검색 (Search)", description = "장소명 자유 텍스트 검색 — 카카오 로컬 키워드 검색 실시간 프록시 + 격자 ID 합성.")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class PlaceSearchController {

	private final PlaceSearchService placeSearchService;

	@Operation(
		summary = "장소 검색 (장소명 → 좌표·격자)",
		description = "카카오 로컬 키워드 검색 결과(정확도순 ≤15건)에 각 좌표의 격자 ID 를 얹어 반환한다. "
			+ "선택 즉시 lat/lng 지도 이동 + gridId 격자 하이라이트. q 누락 400 / trim 후 빈 q·무매치 200 [] / "
			+ "카카오 장애·타임아웃 502(developCode 5502)."
	)
	@GetMapping("/places")
	public SuccessResponse<List<PlaceSearchResponseDto>> searchPlaces(
		@Parameter(description = "검색어 (자유 텍스트 장소명)", example = "부산대")
		@RequestParam String q
	) {
		return SuccessResponse.of(placeSearchService.searchPlaces(q));
	}
}
