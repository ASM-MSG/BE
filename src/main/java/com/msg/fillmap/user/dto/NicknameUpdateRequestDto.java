package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 닉네임 수정 요청 (MSG-203). 검증은 가입(SignupRequestDto)과 메시지까지 동일 — 두 경로의
 * 허용 범위가 어긋나지 않게 한다. 중복 검사는 없다 (FR-6, 닉네임 UNIQUE 없음).
 */
@Schema(description = "닉네임 수정 요청")
public record NicknameUpdateRequestDto(
	@Schema(description = "새 닉네임 (2~20자)", example = "채우미")
	@NotBlank(message = "닉네임은 필수 항목입니다")
	@Size(min = 2, max = 20, message = "닉네임은 2자 이상 20자 이하이어야 합니다")
	String nickname
) {
}
