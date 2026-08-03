package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.User;

/**
 * 내 프로필 응답 (MSG-203). 저장된 값을 가공 없이 노출한다. email 은 이메일(로컬 테스트용) 가입에만
 * 존재하고 카카오 유저는 null 이다 — 카카오에서 이메일을 받지 않기로 확정(MSG-310, 2026-08-03).
 * 조회(GET /me)와 닉네임 수정(PUT /me/nickname)이 공유해 응답 형태가 어긋날 수 없다 (§D2).
 */
@Schema(description = "내 프로필 응답. 조회·닉네임 수정이 같은 형태를 반환한다.",
	requiredProperties = {"nickname"})
public record UserProfileResponseDto(
	@Schema(description = "가입 이메일 — 이메일 가입(로컬 테스트용) 유저만 존재, 카카오 유저는 null (MSG-310)",
		example = "user@fillmap.dev", nullable = true)
	String email,

	@Schema(description = "닉네임 — 카카오 로그인 시 카카오 닉네임이 자동 저장되며, 이후 수정 가능", example = "채우미")
	String nickname
) {

	public static UserProfileResponseDto from(User user) {
		return new UserProfileResponseDto(user.getEmail(), user.getNickname());
	}
}
