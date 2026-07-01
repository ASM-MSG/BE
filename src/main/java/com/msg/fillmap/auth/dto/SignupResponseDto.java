package com.msg.fillmap.auth.dto;

import java.time.LocalDateTime;

import com.msg.fillmap.user.entity.User;

public record SignupResponseDto(
	Long id,
	String email,
	String nickname,
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
