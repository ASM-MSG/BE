package com.msg.fillmap.event.submission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "행사 신청 대표 이미지 presigned URL 발급 응답. uploadUrl 로 S3 에 직접 PUT 업로드한 뒤 "
	+ "s3Key 를 신청 제출·재제출 요청의 imageS3Key 로 전달한다.",
	requiredProperties = {"uploadUrl", "s3Key", "expiresInSec"})
public record EventSubmissionImagePresignResponseDto(
	@Schema(description = "S3 에 직접 PUT 업로드할 presigned URL",
		example = "https://bucket.s3.amazonaws.com/event-submissions/pending/...")
	String uploadUrl,

	@Schema(description = "업로드 대상 S3 객체 키. 제출·재제출 요청에 그대로 전달한다.",
		example = "event-submissions/pending/12/3f0c1f2e-....jpg")
	String s3Key,

	@Schema(description = "presigned URL 유효 시간(초)", example = "600")
	long expiresInSec
) {
}
