package com.msg.fillmap.usergrid.controller;

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
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.usergrid.dto.CollectionGridResponseDto;
import com.msg.fillmap.usergrid.dto.CollectionSummaryResponseDto;
import com.msg.fillmap.usergrid.dto.RegionVideoResponseDto;
import com.msg.fillmap.usergrid.service.CollectionGridView;
import com.msg.fillmap.usergrid.service.CollectionSummaryView;
import com.msg.fillmap.usergrid.service.RegionVideoView;
import com.msg.fillmap.usergrid.service.UserGridQueryService;

/**
 * 개인 도감 요약 API (MSG-152). 3-layer 얇게 — principal 에서 userId 획득 + 서비스 호출 + SuccessResponse 변환만.
 * 구현 패키지는 usergrid, 사용자 대면 리소스는 collections(GridController 의 impl↔resource 분리와 동일).
 */
@Tag(name = "도감 (Collection)", description = "개인 도감 요약 조회 API — 로그인 사용자의 점령·영상·방문 행정동 집계.")
@RestController
@RequestMapping("/api/collections")
@RequiredArgsConstructor
public class CollectionController {

	private final UserGridQueryService userGridQueryService;

	@Operation(
		summary = "개인 도감 요약 조회",
		description = "로그인 사용자의 점령한 격자 수·올린 영상 총합·방문한 행정동 수를 한 번에 반환한다. "
			+ "점령 0건 사용자도 에러 없이 세 값이 모두 0으로 응답한다."
	)
	@GetMapping("/summary")
	public SuccessResponse<CollectionSummaryResponseDto> getSummary(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		CollectionSummaryView view = userGridQueryService.getCollectionSummary(principal.userId());
		return SuccessResponse.of(CollectionSummaryResponseDto.from(view));
	}

	@Operation(
		summary = "갤러리 격자 목록 조회",
		description = "로그인 사용자가 최근 수집한 격자를 first_collected_at 내림차순 최대 30개로 반환한다(무커서). "
			+ "각 항목은 gridId·gridY/gridX·수집/방문 시각·영상 수·cover 영상 ID·cover 썸네일 URL 을 담는다. "
			+ "점령 0건 사용자는 에러 없이 빈 배열을 받는다."
	)
	@GetMapping("/grids")
	public SuccessResponse<List<CollectionGridResponseDto>> getCollectionGrids(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		List<CollectionGridView> views = userGridQueryService.getCollectionGrids(principal.userId());
		return SuccessResponse.of(views.stream().map(CollectionGridResponseDto::from).toList());
	}

	@Operation(
		summary = "동 단위 내 영상 조회",
		description = "행정동(regionCode) 격자들에 올린 로그인 사용자의 영상을 created_at 내림차순으로 반환한다(무커서). "
			+ "regionCode 는 by-grid 응답의 regionCode 를 그대로 넘긴다. 귀속은 격자 축이라 영상 좌표가 옆 동이어도 "
			+ "격자 소속 행정동 기준으로 포함된다. 내 도감이라 PRIVATE·인코딩 중 영상도 포함하며(status ACTIVE 만), "
			+ "그 행정동에 내 영상이 없거나 미존재 regionCode 면 에러 없이 빈 배열을 받는다."
	)
	@GetMapping("/videos")
	public SuccessResponse<List<RegionVideoResponseDto>> getRegionVideos(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "행정동 코드 — by-grid 응답의 regionCode 를 그대로 전달", example = "1168051500")
		@RequestParam String regionCode
	) {
		List<RegionVideoView> views = userGridQueryService.getRegionVideos(principal.userId(), regionCode);
		return SuccessResponse.of(views.stream().map(RegionVideoResponseDto::from).toList());
	}
}
