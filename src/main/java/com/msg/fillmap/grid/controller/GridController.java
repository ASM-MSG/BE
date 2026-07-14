package com.msg.fillmap.grid.controller;

import java.util.List;

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
import com.msg.fillmap.grid.dto.OccupiedGridResponseDto;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.service.GridCellView;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.grid.service.ViewportStrategy;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 격자 색칠 조회 API (MSG-73). 3-layer 얇게 — 파싱 + 서비스 호출 + SuccessResponse 변환만.
 * userId 는 SecurityContext(AuthPrincipal)에서 획득한다(개인 도감).
 */
@RestController
@RequestMapping("/api/grids")
@RequiredArgsConstructor
public class GridController {

	private final GridQueryService gridQueryService;

	@GetMapping("/{gridId}")
	public SuccessResponse<GridCellResponseDto> getCell(
		@AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable String gridId
	) {
		GridCellView view = gridQueryService.getCell(principal.userId(), gridId);
		return SuccessResponse.of(GridCellResponseDto.from(view));
	}

	@GetMapping
	public SuccessResponse<List<OccupiedGridResponseDto>> getOccupiedInViewport(
		@AuthenticationPrincipal AuthPrincipal principal,
		@RequestParam(required = false) Double swLat,
		@RequestParam(required = false) Double swLng,
		@RequestParam(required = false) Double neLat,
		@RequestParam(required = false) Double neLng,
		@RequestParam(required = false, defaultValue = "A") ViewportStrategy strategy
	) {
		ViewportBounds bounds = toBounds(swLat, swLng, neLat, neLng);
		List<OccupiedGridResponseDto> body = gridQueryService.getOccupiedInViewport(principal.userId(), bounds, strategy)
			.stream()
			.map(OccupiedGridResponseDto::from)
			.toList();
		return SuccessResponse.of(body);
	}

	private ViewportBounds toBounds(Double swLat, Double swLng, Double neLat, Double neLng) {
		if (swLat == null || swLng == null || neLat == null || neLng == null) {
			throw new ApiException(GridErrorCode.INVALID_VIEWPORT);
		}
		return new ViewportBounds(swLat, swLng, neLat, neLng);
	}
}
