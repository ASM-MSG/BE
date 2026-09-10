package com.msg.fillmap.friend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.friend.entity.FriendshipStatus;

@Schema(description = "친구 요청 생성 응답", requiredProperties = {"status"})
public record FriendRequestCreateResponseDto(
	@Schema(description = "PENDING = 요청이 등록돼 상대 수락 대기, "
		+ "ACCEPTED = 상대가 먼저 보낸 요청이 있어 즉시 친구 성립(자동 수락 — FR-8). "
		+ "FE 는 이 값으로 \"요청 보냄\"과 \"친구가 됐어요\" 화면을 구분한다.")
	FriendshipStatus status
) {
}
