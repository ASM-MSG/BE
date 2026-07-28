package com.msg.fillmap.zone.controller;

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
import com.msg.fillmap.zone.dto.SearchResultResponseDto;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * 장소 검색 API (MSG-234 §D6). zones.name → regions.region_name 2단 폴백으로 네이버 지도 SDK 의
 * 키워드 검색 부재를 우리 DB 가 메운다. 3-layer 얇게 — 파싱 + 서비스 호출 + SuccessResponse 변환만.
 */
@Tag(name = "장소 검색 (Search)", description = "구역(zone)·행정동(region) 2단 폴백 키워드 검색. 결과 bbox 로 지도를 이동한다.")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

	private final ZoneQueryService zoneQueryService;

	@Operation(
		summary = "장소 검색 (zones → regions 2단 폴백)",
		description = "q 로 구역을 먼저 찾고(zones.name ILIKE), 매치가 없을 때만 행정동으로 폴백한다(regions.region_name ILIKE). "
			+ "매치 0 이면 200 + 빈 배열, q 누락은 400. limit 은 1~50 clamp 후 두 분기 모두 적용."
	)
	@GetMapping
	public SuccessResponse<List<SearchResultResponseDto>> search(
		@Parameter(description = "검색어", example = "서면")
		@RequestParam String q,
		@Parameter(description = "최대 결과 수 (1~50 clamp)", example = "10")
		@RequestParam(required = false, defaultValue = "10") int limit
	) {
		return SuccessResponse.of(zoneQueryService.search(q, limit));
	}
}
