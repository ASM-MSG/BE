package com.msg.fillmap.badge.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.badge.dto.FeaturedBadgeRequestDto;
import com.msg.fillmap.badge.dto.FeaturedBadgeResponseDto;
import com.msg.fillmap.badge.dto.MyBadgeResponseDto;
import com.msg.fillmap.badge.service.BadgeFeaturedService;
import com.msg.fillmap.badge.service.BadgeQueryService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 뱃지 API (교체 = MSG-239 · 조회 = MSG-201). 3-layer 얇게 — principal userId + 서비스 호출 +
 * SuccessResponse 변환만.
 */
@Tag(name = "뱃지 (Badge)", description = "뱃지 API — 내 뱃지 목록 조회 · 대표 뱃지 집합 교체.")
@RestController
@RequestMapping("/api/badges")
@RequiredArgsConstructor
public class BadgeController {

	private final BadgeFeaturedService badgeFeaturedService;
	private final BadgeQueryService badgeQueryService;

	@Operation(
		summary = "내 뱃지 전체 목록",
		description = "시딩된 뱃지를 내 획득 상태와 함께 시딩 순(badges.id 오름차순)으로 반환한다. 은퇴 뱃지"
			+ "(retired_at 있음)는 획득자에게만 보이고 미획득자 목록에서는 빠진다 — 그래서 사용자마다 행 수가 "
			+ "다를 수 있다. 미획득 행은 earned false·earnedAt null·isNew false·featuredRank null. 이번 응답에 "
			+ "노출된 미확인(새 뱃지) 행은 자동으로 확인 처리되어 다음 조회부터 isNew false 가 된다."
	)
	@GetMapping
	public SuccessResponse<List<MyBadgeResponseDto>> findMyBadges(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(badgeQueryService.findMyBadges(principal.userId()));
	}

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
