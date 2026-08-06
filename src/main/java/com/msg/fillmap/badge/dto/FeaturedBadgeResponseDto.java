package com.msg.fillmap.badge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.badge.repository.FeaturedBadgeProjection;

/**
 * 적용된 대표 뱃지 (PUT /api/badges/featured 응답 — MSG-239 §API 명세). rank = 표시 순서(1·2).
 */
@Schema(description = "적용된 대표 뱃지", requiredProperties = {"badgeId", "code", "name", "rank", "iconUrl"})
public record FeaturedBadgeResponseDto(
	@Schema(description = "뱃지 ID", example = "3")
	Long badgeId,

	@Schema(description = "뱃지 code", example = "EXPLORER_50")
	String code,

	@Schema(description = "표시명", example = "탐험가 II")
	String name,

	@Schema(description = "아이콘 URL (에셋 확정 전 null)", example = "null", nullable = true)
	String iconUrl,

	@Schema(description = "표시 순서 (1·2)", example = "1")
	Integer rank
) {

	public static FeaturedBadgeResponseDto from(FeaturedBadgeProjection projection) {
		return new FeaturedBadgeResponseDto(
			projection.getBadgeId(), projection.getCode(), projection.getName(),
			projection.getIconUrl(), projection.getRank());
	}
}
