package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

@Schema(description = "프로필 이미지 변경 확정 요청 (MSG-373)")
public record ProfileImageUpdateRequestDto(
	@Schema(description = "presign 발급으로 받은 pending 키. 그 URL 로 업로드를 마친 뒤 그대로 전달한다.",
		example = "profiles/pending/42/3f0c1f2e-....jpg")
	@NotBlank String s3Key
) {
}
