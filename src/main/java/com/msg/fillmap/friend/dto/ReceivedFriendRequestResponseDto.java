package com.msg.fillmap.friend.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
	description = "받은 친구 요청 응답 — 최신 요청 우선 정렬.",
	requiredProperties = {"requesterId", "nickname", "requestedAt", "profileImageUrl"}
)
public record ReceivedFriendRequestResponseDto(
	@Schema(description = "보낸 사용자 id — 수락/거절 호출의 경로 변수로 그대로 쓴다", example = "3")
	Long requesterId,

	@Schema(description = "보낸 사용자의 닉네임", example = "채우미")
	String nickname,

	@Schema(description = "보낸 사용자의 프로필 이미지 URL — 미설정이면 null", nullable = true)
	String profileImageUrl,

	@Schema(description = "요청 시각", example = "2026-08-03T12:00:00Z")
	LocalDateTime requestedAt
) {
}
