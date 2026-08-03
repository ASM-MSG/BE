package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.User;

/**
 * 내 프로필 응답 (MSG-203). 소셜 로그인이 자동 저장한 값을 가공 없이 노출한다 — email 필드는
 * 응답에 항상 존재(required)하되, 카카오 가입이 이메일을 수집하지 않아 값은 null 일 수 있다
 * (V16 · MSG-310, Jackson 기본 직렬화라 null 필드도 생략되지 않음 = required + nullable).
 * 조회(GET /me)와 닉네임 수정(PUT /me/nickname)이 공유해 응답 형태가 어긋날 수 없다 (§D2).
 */
@Schema(description = "내 프로필 응답. 조회·닉네임 수정이 같은 형태를 반환한다.",
	requiredProperties = {"email", "nickname"})
public record UserProfileResponseDto(
	@Schema(description = "가입 이메일 — 이메일 가입 시 저장된 값. 카카오 가입은 이메일을 수집하지 않아 null (MSG-310)",
		example = "user@fillmap.dev", nullable = true)
	String email,

	@Schema(description = "닉네임 — 카카오 로그인 시 카카오 닉네임이 자동 저장되며, 이후 수정 가능", example = "채우미")
	String nickname
) {

	public static UserProfileResponseDto from(User user) {
		return new UserProfileResponseDto(user.getEmail(), user.getNickname());
	}
}
