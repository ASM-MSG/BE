package com.msg.fillmap.grid.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.dto.GridCellResponseDto;
import com.msg.fillmap.grid.dto.OccupiedGridPageResponseDto;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.service.GridCellView;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 격자 색칠 조회 API (MSG-73 · MSG-90 페이지네이션). 3-layer 얇게 — 파싱 + 서비스 호출 + SuccessResponse 변환만.
 * userId 는 SecurityContext(AuthPrincipal)에서 획득한다(개인 도감).
 */
@Tag(name = "격자 (Grid)", description = "개인 도감 색칠 격자 조회 API — 로그인 사용자가 점령한 격자만 반환한다.")
@RestController
@RequestMapping("/api/grids")
@RequiredArgsConstructor
public class GridController {

	private final GridQueryService gridQueryService;

	@Operation(
		summary = "단일 격자 색칠 상태 조회",
		description = "특정 격자를 내가 점령(색칠)했는지와 내 영상 수를 반환한다. "
			+ "미점령 격자도 404가 아니라 occupied=false로 응답한다."
	)
	@GetMapping("/{gridId}")
	public SuccessResponse<GridCellResponseDto> getCell(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "41642_110458")
		@PathVariable String gridId
	) {
		GridCellView view = gridQueryService.getCell(principal.userId(), gridId);
		return SuccessResponse.of(GridCellResponseDto.from(view));
	}

	@Operation(
		summary = "뷰포트 내 색칠 격자 조회 (커서 페이지네이션)",
		description = "지도 화면 bbox(남서~북동 좌표) 안에서 내가 점령한 격자를 (grid_y, grid_x) 오름차순으로 반환한다. "
			+ "응답의 nextCursor를 다음 요청 cursor에 넣어 이어서 조회한다. bbox 한 변의 span은 최대 0.5도."
	)
	@GetMapping
	public SuccessResponse<OccupiedGridPageResponseDto> getOccupiedInViewport(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "남서 모서리 위도", example = "37.50")
		@RequestParam(required = false) Double swLat,
		@Parameter(description = "남서 모서리 경도", example = "127.00")
		@RequestParam(required = false) Double swLng,
		@Parameter(description = "북동 모서리 위도", example = "37.55")
		@RequestParam(required = false) Double neLat,
		@Parameter(description = "북동 모서리 경도", example = "127.05")
		@RequestParam(required = false) Double neLng,
		@Parameter(description = "다음 페이지 커서 (직전 응답의 nextCursor). 첫 페이지는 생략", example = "NDE2NDNfMTEwNDYw")
		@RequestParam(required = false) String cursor,
		@Parameter(description = "페이지 크기 (기본 1000, 최대 5000)", example = "1000")
		@RequestParam(required = false, defaultValue = "1000") int size
	) {
		ViewportBounds bounds = toBounds(swLat, swLng, neLat, neLng);
		OccupiedGridPage page = gridQueryService.getOccupiedInViewport(principal.userId(), bounds, cursor, size);
		return SuccessResponse.of(OccupiedGridPageResponseDto.from(page));
	}

	private ViewportBounds toBounds(Double swLat, Double swLng, Double neLat, Double neLng) {
		if (swLat == null || swLng == null || neLat == null || neLng == null) {
			throw new ApiException(GridErrorCode.INVALID_VIEWPORT);
		}
		return new ViewportBounds(swLat, swLng, neLat, neLng);
	}
}
