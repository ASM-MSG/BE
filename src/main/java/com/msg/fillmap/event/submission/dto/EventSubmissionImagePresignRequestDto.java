package com.msg.fillmap.event.submission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "행사 신청 대표 이미지 업로드용 presigned URL 발급 요청 (MSG-498)")
public record EventSubmissionImagePresignRequestDto(
	@Schema(description = "이미지 파일 확장자 (점 없이). jpg, jpeg, png 만 — 시안 문구가 \"JPG 또는 PNG\"라 webp 는 받지 않는다",
		example = "jpg")
	@NotBlank String extension,

	@Schema(description = "이미지 MIME 타입. 확장자와 쌍이 맞아야 한다", example = "image/jpeg")
	@NotBlank String contentType,

	@Schema(description = "업로드할 파일 크기(바이트). 10MB 초과 시 거부", example = "1048576")
	@NotNull @Positive Long contentLength
) {
}
