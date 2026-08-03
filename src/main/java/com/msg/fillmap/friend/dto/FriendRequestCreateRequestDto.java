package com.msg.fillmap.friend.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "친구 요청 생성 요청")
public record FriendRequestCreateRequestDto(
	@Schema(description = "상대의 고정 친구 코드", example = "AB3DE7GH")
	@NotBlank(message = "친구 코드는 필수 항목입니다")
	String friendCode
) {
}
