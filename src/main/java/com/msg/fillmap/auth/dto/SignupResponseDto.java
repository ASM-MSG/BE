package com.msg.fillmap.auth.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.User;

@Schema(description = "회원가입 성공 응답 — 생성된 사용자 정보",
	requiredProperties = {"id", "email", "nickname", "createdAt"})
public record SignupResponseDto(
	@Schema(description = "생성된 사용자 ID", example = "1")
	Long id,

	@Schema(description = "가입 이메일", example = "user@fillmap.dev")
	String email,

	@Schema(description = "닉네임", example = "채우미")
	String nickname,

	@Schema(description = "가입 시각", example = "2026-07-17T20:11:03Z")
	LocalDateTime createdAt
) {

	public static SignupResponseDto from(User user) {
		return new SignupResponseDto(
			user.getId(),
			user.getEmail(),
			user.getNickname(),
			user.getCreatedAt()
		);
	}
}
