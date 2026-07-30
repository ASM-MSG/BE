package com.msg.fillmap.badge.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.badge.dto.FeaturedBadgeRequestDto;
import com.msg.fillmap.badge.dto.FeaturedBadgeResponseDto;
import com.msg.fillmap.badge.service.BadgeFeaturedService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 뱃지 API (MSG-239). 3-layer 얇게 — principal userId + 서비스 호출 + SuccessResponse 변환만.
 * 조회(GET) 계열은 MSG-201 소관 — 이 티켓은 대표 뱃지 교체까지.
 */
@Tag(name = "뱃지 (Badge)", description = "뱃지 API — 대표 뱃지 집합 교체.")
@RestController
@RequestMapping("/api/badges")
@RequiredArgsConstructor
public class BadgeController {

	private final BadgeFeaturedService badgeFeaturedService;

	@Operation(
		summary = "대표 뱃지 집합 교체",
		description = "획득한 뱃지 중 최대 2개를 대표로 교체 지정한다(멱등). 배열 순서 = 표시 순서(rank 1·2), "
			+ "빈 배열은 전부 해제. 미획득·미존재 뱃지는 7403, 중복 id 는 7400, 3개 이상은 400 이다."
	)
	@PutMapping("/featured")
	public SuccessResponse<List<FeaturedBadgeResponseDto>> replaceFeatured(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody FeaturedBadgeRequestDto request
	) {
		return SuccessResponse.of(badgeFeaturedService.replaceFeatured(principal.userId(), request.badgeIds()));
	}
}
