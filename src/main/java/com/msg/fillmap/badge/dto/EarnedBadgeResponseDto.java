package com.msg.fillmap.badge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.badge.repository.EligibleBadgeProjection;

/**
 * 새로 획득한 뱃지 (업로드 응답 동봉용 — FR-9). conditionType·condition_value 는 노출하지 않는다
 * (위키 방침·FE 불요, MSG-239 §API 명세).
 */
@Schema(description = "이번 행동으로 새로 획득한 뱃지", requiredProperties = {"badgeId", "code", "name", "description", "iconUrl"})
public record EarnedBadgeResponseDto(
	@Schema(description = "뱃지 ID", example = "1")
	Long badgeId,

	@Schema(description = "뱃지 code", example = "EXPLORER_1")
	String code,

	@Schema(description = "표시명", example = "첫 발자국")
	String name,

	@Schema(description = "설명 — badges.description 은 NULL 허용 컬럼이다", example = "첫 격자를 수집했어요",
		nullable = true)
	String description,

	@Schema(description = "아이콘 URL (에셋 확정 전 null)", example = "null", nullable = true)
	String iconUrl
) {

	public static EarnedBadgeResponseDto from(EligibleBadgeProjection projection) {
		return new EarnedBadgeResponseDto(
			projection.getBadgeId(), projection.getCode(), projection.getName(),
			projection.getDescription(), projection.getIconUrl());
	}
}
