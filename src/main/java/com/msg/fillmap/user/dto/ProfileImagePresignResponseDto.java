package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로필 이미지 presigned URL 발급 응답. uploadUrl 로 S3 에 직접 PUT 업로드한 뒤 "
	+ "s3Key 로 변경 확정(PUT /api/users/me/profile-image)을 호출한다.",
	requiredProperties = {"uploadUrl", "s3Key", "expiresInSec"})
public record ProfileImagePresignResponseDto(
	@Schema(description = "S3 에 직접 PUT 업로드할 presigned URL", example = "https://bucket.s3.amazonaws.com/profiles/...")
	String uploadUrl,

	@Schema(description = "업로드 대상 S3 객체 키. 변경 확정 요청에 그대로 전달한다.",
		example = "profiles/pending/42/3f0c1f2e-....jpg")
	String s3Key,

	@Schema(description = "presigned URL 유효 시간(초)", example = "600")
	long expiresInSec
) {
}
