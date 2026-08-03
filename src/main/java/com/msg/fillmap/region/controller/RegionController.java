package com.msg.fillmap.region.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.region.dto.RegionResponseDto;
import com.msg.fillmap.region.dto.RegionStatResponseDto;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionStatsQueryService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 위치 검색 API (MSG-93 · 역지오코딩). 3-layer 얇게 — 파싱 + 서비스 호출 + SuccessResponse 변환만.
 * 좌표→행정동 판정이라 사용자별 데이터가 아니지만, 지도 화면 뒤 조회이므로 로그인 필요(SecurityConfig anyRequest).
 */
@Tag(name = "행정동 (Region)", description = "좌표를 포함하는 행정동을 우리 region_code 체계로 판정하는 역지오코딩 API.")
@RestController
@RequestMapping("/api/regions")
@RequiredArgsConstructor
public class RegionController {

	private final RegionQueryService regionQueryService;
	private final RegionStatsQueryService regionStatsQueryService;

	@Operation(
		summary = "역지오코딩 (좌표 → 행정동)",
		description = "좌표를 포함하는 행정동 1건을 반환한다. 포함 행정동이 없으면(바다·국외) 404가 아니라 200 + data null. "
			+ "서비스 좌표 범위(한국) 밖이면 400(6400)."
	)
	@GetMapping("/reverse-geocode")
	public SuccessResponse<RegionResponseDto> reverseGeocode(
		@Parameter(description = "위도", example = "37.4979")
		@RequestParam(required = false) Double lat,
		@Parameter(description = "경도", example = "127.0276")
		@RequestParam(required = false) Double lon
	) {
		if (lat == null || lon == null) {
			throw new ApiException(RegionErrorCode.INVALID_COORDINATE);
		}
		RegionResponseDto body = regionQueryService.resolveByPoint(lat, lon)
			.map(RegionResponseDto::from)
			.orElse(null);
		return SuccessResponse.of(body);
	}

	@Operation(
		summary = "내 행정동별 수집률 조회",
		description = "로그인 사용자가 점령(수집)한 격자를 행정동별로 집계한 수집률 리스트를 반환한다. "
			+ "parentCode 로 시군구를 좁힐 수 있고(실존하지 않는 코드면 404/6404), collectedOnly=false 면 "
			+ "롤백으로 0이 된 행정동도 포함한다. 수집이 없으면 404 가 아니라 200 + 빈 배열."
	)
	@GetMapping("/stats")
	public SuccessResponse<List<RegionStatResponseDto>> getStats(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "상위 시군구 코드. 생략하면 전국. 실존하지 않으면 6404", example = "11680")
		@RequestParam(required = false) String parentCode,
		@Parameter(description = "true=수집한 행정동만, false=손댄 행정동 전부(롤백 0-row 포함)", example = "true")
		@RequestParam(required = false, defaultValue = "true") boolean collectedOnly
	) {
		List<RegionStatResponseDto> body = regionStatsQueryService.findStats(principal.userId(), parentCode, collectedOnly)
			.stream()
			.map(RegionStatResponseDto::from)
			.toList();
		return SuccessResponse.of(body);
	}

	@Operation(
		summary = "현재 위치 행정동 탐험률 (좌표 → 수집률)",
		description = "도감 갤러리 진입 초기값. 현재 위치 좌표가 속한 행정동 1건의 내 수집률을 반환한다. 그 행정동에 수집이 없어도 "
			+ "0% 로 합성해 반환하고, 어떤 행정동에도 안 속하면(바다·국외) 404 가 아니라 200 + data null. 서비스 범위 밖 좌표는 400(6400)."
	)
	@GetMapping("/stats/by-point")
	public SuccessResponse<RegionStatResponseDto> getStatByPoint(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "위도", example = "37.4979")
		@RequestParam(required = false) Double lat,
		@Parameter(description = "경도", example = "127.0276")
		@RequestParam(required = false) Double lon
	) {
		if (lat == null || lon == null) {
			throw new ApiException(RegionErrorCode.INVALID_COORDINATE);
		}
		RegionStatResponseDto body = regionStatsQueryService.findStatByPoint(principal.userId(), lat, lon)
			.map(RegionStatResponseDto::from)
			.orElse(null);
		return SuccessResponse.of(body);
	}

	@Operation(
		summary = "격자 중심 행정동 탐험률 (격자 클릭 → 수집률)",
		description = "클릭한 격자의 중심점이 속한 행정동 1건의 내 수집률을 반환한다. 귀속 축이 수집률 집계(MSG-155)와 같아 탐험률·라벨이 "
			+ "일치한다. 중심점이 어떤 행정동에도 안 속하거나 gridId 형식이 이상하면 200 + data null(별도 에러 코드 없음)."
	)
	@GetMapping("/stats/by-grid")
	public SuccessResponse<RegionStatResponseDto> getStatByGrid(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "격자 ID \"{grid_y}_{grid_x}\"", example = "41642_110458")
		@RequestParam(required = false) String gridId
	) {
		RegionStatResponseDto body = regionStatsQueryService.findStatByGrid(principal.userId(), gridId)
			.map(RegionStatResponseDto::from)
			.orElse(null);
		return SuccessResponse.of(body);
	}
}
