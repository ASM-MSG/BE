package com.msg.fillmap.badge.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 대표 뱃지 집합 교체 요청 (PUT /api/badges/featured — MSG-239 §D6). 집합 교체 시맨틱(멱등):
 * 배열 순서 = featured_rank 순서, 빈 배열 = 전부 해제. 3개 이상은 @Size 로 global 400(§D7).
 */
@Schema(description = "대표 뱃지 집합 교체 요청 — 배열 순서가 표시 순서, 빈 배열은 전부 해제")
public record FeaturedBadgeRequestDto(
	@Schema(description = "대표로 지정할 뱃지 id 목록 (최대 2개, 순서 = 표시 순서)", example = "[3, 7]")
	@NotNull
	@Size(max = 2)
	List<@NotNull Long> badgeIds
) {
}
