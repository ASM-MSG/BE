package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 내가 차단한 사용자 목록 항목 (MSG-569 FR-4). 닉네임·프로필 이미지는 조회 시점 users 값이라 사본이 없다
 * (MSG-371 원칙). userId 가 해제(DELETE /api/users/{userId}/block)의 경로 값이다.
 */
@Schema(description = "내가 차단한 사용자 목록 항목",
	requiredProperties = {"userId", "nickname", "profileImageUrl", "blockedAt"})
public record BlockedUserResponseDto(
	@Schema(description = "차단한 사용자 ID. 해제(DELETE /api/users/{userId}/block)의 경로 값", example = "42")
	Long userId,
	@Schema(description = "닉네임 원문(조회 시점 값, 사본 아님)", example = "busan.vlog")
	String nickname,
	@Schema(description = "프로필 이미지 URL. 없으면 null", nullable = true)
	String profileImageUrl,
	@Schema(description = "차단 시각(최초 차단 시각, 재차단해도 바뀌지 않는다)", example = "2026-09-08T03:10:00Z")
	LocalDateTime blockedAt
) {
}
