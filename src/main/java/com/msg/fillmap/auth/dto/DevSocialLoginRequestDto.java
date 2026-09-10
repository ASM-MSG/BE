package com.msg.fillmap.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

@Schema(description = "[로컬/dev 전용] 소셜 로그인 모의 요청 — 실제 소셜 ID Token 없이 (provider, oid)로 로그인/가입한다.")
public record DevSocialLoginRequestDto(
	@Schema(description = "소셜 제공자 (기본 KAKAO)", example = "KAKAO")
	String provider,

	@Schema(description = "소셜 고유 식별자(oid). 같은 값이면 같은 사용자로 재로그인된다.", example = "dev-kakao-1")
	@NotBlank String oid,

	@Schema(description = "이메일 (선택). 없으면 {oid}@dev.local", example = "kakaouser@dev.local")
	String email,

	@Schema(description = "닉네임 (선택). 없으면 dev-{oid}", example = "카카오테스터")
	String nickname
) {

	public String resolvedEmail() {
		return (email == null || email.isBlank()) ? oid + "@dev.local" : email;
	}

	public String resolvedNickname() {
		return (nickname == null || nickname.isBlank()) ? "dev-" + oid : nickname;
	}
}
