package com.msg.fillmap.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.video.dto.ExploreSort;
import com.msg.fillmap.video.dto.RegionExploreResponseDto;
import com.msg.fillmap.video.dto.RegionExplorePageResponseDto;
import com.msg.fillmap.video.service.RegionExploreService;

/**
 * 전역 탐색 = 행정동 축 전역 공개 콘텐츠 조회 (MSG-238). 경로 접두사는 /api/regions 지만
 * 게이트·커버·presigner 등 videos 자산을 다루는 Owner B 코드라 video 패키지에 둔다
 * (§D5 — 127/87 "경로 접두사가 소유권을 강제하지 않음" 선례). RegionController(A)의
 * 리터럴 경로들과 충돌 없음. 전체 지역 목록은
 * MSG-460부터 로그인 사용자의 최근 업로드 지역을 정렬에 반영한다.
 */
@Tag(name = "전역 탐색 (Region Explore)", description = "행정동 축으로 전역 공개 콘텐츠를 탐색하는 API — "
	+ "지도 홈 패널·전체 보기 격자 썸네일 뷰·검색 무입력 전체 지역 리스트.")
@RestController
@RequiredArgsConstructor
public class RegionExploreController {

	private final RegionExploreService regionExploreService;

	@Operation(
		summary = "행정동 격자 카드 리스트 + 헤더 카운트 조회",
		description = "그 행정동 격자들 중 전역 공개 콘텐츠(공개·인코딩 완료·타인 영상 포함)가 있는 격자를 카드로 "
			+ "반환한다. 카운트(gridCount·videoCount)는 limit 무관 전체 기준이라 지도 홈 패널(sort=LATEST&limit=20, "
			+ "SRS FR-MAP-10)과 전체 보기(limit 생략)가 같은 값을 받지만, **전역 공개 콘텐츠를 센 값이라 패널 "
			+ "헤더(\"이 지역 격자 N개 · 영상 M개\")에 쓰면 안 된다** — 헤더는 내 도감 집계 응답의 currentRegion"
			+ "(중심 동 전체의 내 것, MSG-374)이 채운다. 카드 커버는 격자 대표(cover)와 같은 영상이고 썸네일은 "
			+ "presigned GET URL 이다. 미존재·무콘텐츠 regionCode 는 404 가 아니라 200 + 카운트 0·빈 배열이다."
	)
	@GetMapping("/api/regions/{regionCode}/grids")
	public SuccessResponse<RegionExploreResponseDto> getRegionGrids(
		@Parameter(description = "행정동 코드 — reverse-geocode·전체 지역 리스트의 regionCode 를 그대로 전달",
			example = "2644056000")
		@PathVariable String regionCode,
		@Parameter(description = "정렬 — POPULAR(조회수 합)·LATEST(최신 공개 영상). 대문자 전용이며 "
			+ "소문자 포함 무효 값은 400 이다. 지도 홈 패널은 LATEST (SRS FR-MAP-10, 생략 기본값은 POPULAR 유지)",
			example = "LATEST")
		@RequestParam(defaultValue = "POPULAR") ExploreSort sort,
		@Parameter(description = "카드 수 상한 — 지도 홈 패널은 20 (SRS FR-MAP-10). 생략하면 전부, 1 미만은 1 로 보정한다",
			example = "20")
		@RequestParam(required = false) Integer limit
	) {
		return SuccessResponse.of(regionExploreService.getRegionGrids(regionCode, sort, limit));
	}

	@Operation(
		summary = "전체 지역 리스트 조회",
		description = "전역 공개 콘텐츠가 있는 행정동을 20개씩 반환한다. 로그인 사용자가 직접 최근 "
			+ "업로드한 지역이 먼저 나오고 나머지는 격자 수 내림차순이다. hasNext가 true면 nextCursor를 "
			+ "다음 요청의 "
			+ "cursor에 그대로 전달한다."
	)
	@GetMapping("/api/regions/explore")
	public SuccessResponse<RegionExplorePageResponseDto> getExploreRegions(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "직전 응답의 nextCursor. 첫 페이지는 생략")
		@RequestParam(required = false) String cursor
	) {
		return SuccessResponse.of(RegionExplorePageResponseDto.from(
			regionExploreService.getExploreRegions(userIdOrNull(principal), cursor)));
	}

	/** 비로그인 요청의 principal 은 null 이다 (MSG-491, MissionController·EventVideoController 와 같은 형태). */
	private Long userIdOrNull(AuthPrincipal principal) {
		return principal == null ? null : principal.userId();
	}
}
