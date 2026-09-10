package com.msg.fillmap.friend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
	description = "친구 코드 미리보기 응답 — 요청 확정 전 확인 화면(\"OOO님에게 요청을 보낼까요?\")용. "
		+ "관계 상태(relation)를 함께 담아 화면이 요청 버튼의 활성 여부·문구를 미리 정할 수 있다 (MSG-391). "
		+ "조회 전용이며 요청 API 가 전 검증을 재수행한다.",
	requiredProperties = {"nickname", "relation"}
)
public record FriendPreviewResponseDto(
	@Schema(description = "코드 소유자의 닉네임 — SELF 면 내 닉네임", example = "채우미")
	String nickname,
	@Schema(description = "조회자와 코드 소유자의 관계 상태 — 조회 시점 실시간 판정 (MSG-391)", example = "NONE")
	FriendRelation relation
) {
}
