package com.msg.fillmap.friend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "내 친구 코드 응답", requiredProperties = {"friendCode"})
public record FriendCodeResponseDto(
	@Schema(description = "고정 친구 코드 — 혼동 문자(I·O·0·1) 제외 32종 8자, 재발급 없음", example = "AB3DE7GH")
	String friendCode
) {
}
