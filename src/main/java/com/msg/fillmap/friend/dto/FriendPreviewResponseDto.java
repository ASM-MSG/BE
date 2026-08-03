package com.msg.fillmap.friend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
	description = "친구 코드 미리보기 응답 — 요청 확정 전 확인 화면(\"OOO님에게 요청을 보낼까요?\")용. "
		+ "조회 전용이며 요청 API 가 전 검증을 재수행한다.",
	requiredProperties = {"nickname"}
)
public record FriendPreviewResponseDto(
	@Schema(description = "코드 소유자의 닉네임", example = "채우미")
	String nickname
) {
}
