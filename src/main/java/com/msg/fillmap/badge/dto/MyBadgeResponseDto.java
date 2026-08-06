package com.msg.fillmap.badge.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.badge.repository.MyBadgeProjection;

/**
 * 내 뱃지 목록 행 (GET /api/badges 응답 — docs/MSG-201.md §D2). 획득+미획득 전체 뱃지가 한 배열로
 * 나가고, 미획득 행은 earned false + earnedAt·featuredRank null + isNew false 다(3값 boolean 금지).
 * 진열장은 FE 가 earned true 를 earnedAt 내림차순 정렬해 파생한다(§D4) — 별도 필드 없음.
 */
@Schema(description = "내 뱃지 목록 행 — 획득+미획득 전체",
	requiredProperties = {"badgeId", "code", "name", "earned", "isNew", "description", "iconUrl", "earnedAt",
		"featuredRank"})
public record MyBadgeResponseDto(
	@Schema(description = "뱃지 ID", example = "2")
	Long badgeId,

	@Schema(description = "뱃지 code", example = "EXPLORER_10")
	String code,

	@Schema(description = "표시명", example = "탐험가 I")
	String name,

	@Schema(description = "설명 — badges.description 은 NULL 허용 컬럼이다", example = "격자 10개를 수집했어요",
		nullable = true)
	String description,

	@Schema(description = "아이콘 URL (에셋 확정 전 null)", example = "null", nullable = true)
	String iconUrl,

	@Schema(description = "획득 여부", example = "true")
	Boolean earned,

	@Schema(description = "획득 시각 — 미획득이면 null", example = "2026-07-29T11:02:31", nullable = true)
	LocalDateTime earnedAt,

	@Schema(description = "미확인(새 뱃지) 여부 — 미획득이면 false", example = "false")
	Boolean isNew,

	@Schema(description = "대표 뱃지 순서(1·2) — 대표 아니면 null", example = "1", nullable = true)
	Integer featuredRank
) {

	public static MyBadgeResponseDto from(MyBadgeProjection projection) {
		return new MyBadgeResponseDto(
			projection.getBadgeId(), projection.getCode(), projection.getName(),
			projection.getDescription(), projection.getIconUrl(), projection.getEarned(),
			projection.getEarnedAt(), projection.getIsNew(), projection.getFeaturedRank());
	}
}
