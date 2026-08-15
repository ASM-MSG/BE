package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.User;

/**
 * 내 프로필 응답 (MSG-203). 소셜 로그인이 자동 저장한 값을 가공 없이 노출한다 — email 필드는
 * 응답에 항상 존재(required)하되, 카카오 가입이 이메일을 수집하지 않아 값은 null 일 수 있다
 * (V16 · MSG-310, Jackson 기본 직렬화라 null 필드도 생략되지 않음 = required + nullable).
 * 조회(GET /me)와 닉네임 수정(PUT /me/nickname), 프로필 이미지 변경·제거(MSG-373)가 공유해
 * 응답 형태가 어긋날 수 없다 (§D2).
 */
@Schema(description = "내 프로필 응답. 조회·닉네임 수정·프로필 이미지 변경·위치정보 동의 변경이 같은 형태를 반환한다.",
	requiredProperties = {"email", "nickname", "profileImageUrl", "createdAt", "locationConsent"})
public record UserProfileResponseDto(
	@Schema(description = "가입 이메일 — 이메일 가입 시 저장된 값. 카카오 가입은 이메일을 수집하지 않아 null (MSG-310)",
		example = "user@fillmap.dev", nullable = true)
	String email,

	@Schema(description = "닉네임 — 카카오 로그인 시 카카오 닉네임이 자동 저장되며, 이후 수정 가능", example = "채우미")
	String nickname,

	@Schema(description = "프로필 이미지 공개 URL — 미설정이면 null 이고 기본 프로필 표시는 FE 몫이다 (MSG-373)",
		example = "https://fillmap-video-dev.s3.ap-northeast-2.amazonaws.com/profiles/original/42/uuid.jpg",
		nullable = true)
	String profileImageUrl,

	@Schema(description = "가입 시각 — DB 저장값(UTC) 그대로다. \"2026.01.12\" 같은 표기는 FE 몫 (MSG-373)",
		example = "2026-01-12T03:24:11Z")
	LocalDateTime createdAt,

	@Schema(description = "위치기반서비스 이용 동의 여부 — 가입 직후는 false 다. 마지막 변경 시각은 서버에만 두고 "
		+ "응답에 싣지 않는다 (MSG-402 §D-6)", example = "false")
	Boolean locationConsent
) {

	public static UserProfileResponseDto from(User user) {
		return new UserProfileResponseDto(
			user.getEmail(), user.getNickname(), user.getProfileImageUrl(), user.getCreatedAt(),
			user.isLocationConsent());
	}
}
