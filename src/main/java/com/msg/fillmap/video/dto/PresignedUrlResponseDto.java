package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "presigned URL 발급 응답. uploadUrl로 S3에 직접 PUT 업로드 후, s3Key로 메타데이터 저장(POST /api/videos)을 호출한다.")
public record PresignedUrlResponseDto(
	@Schema(description = "S3에 직접 PUT 업로드할 presigned URL", example = "https://bucket.s3.amazonaws.com/videos/...")
	String uploadUrl,

	@Schema(description = "업로드 대상 S3 객체 키. 이후 메타데이터 저장 요청에 그대로 전달한다.", example = "videos/2026/07/uuid.mp4")
	String s3Key,

	@Schema(description = "presigned URL 유효 시간(초)", example = "300")
	long expiresInSec
) {
}
