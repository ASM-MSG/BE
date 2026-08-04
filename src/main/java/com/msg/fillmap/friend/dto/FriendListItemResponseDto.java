package com.msg.fillmap.friend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.GridColor;

/**
 * 친구 목록 항목 응답 (MSG-186 FR-3). 수락된 친구 한 명을 식별·표시할 최소 정보만 담는다 —
 * 수락 시각 등은 FR 에 없어 노출하지 않는다. JPQL 생성자 프로젝션 타깃 겸 응답 DTO
 * (ReceivedFriendRequestResponseDto 선례).
 */
@Schema(
	description = "친구 목록 항목 — 수락된 친구 한 명.",
	requiredProperties = {"userId", "nickname", "gridColor"}
)
public record FriendListItemResponseDto(
	@Schema(description = "친구의 사용자 id — 프로필 조회·친구 삭제 경로 변수로 그대로 쓴다", example = "7")
	Long userId,

	@Schema(description = "친구의 닉네임", example = "채우미")
	String nickname,

	@Schema(description = "친구의 프로필 이미지 URL — 미설정이면 null", nullable = true)
	String profileImageUrl,

	@Schema(description = "친구의 도감 색상 — 지도에서 친구가 수집한 격자를 칠하는 색", example = "PINK")
	GridColor gridColor
) {
}
