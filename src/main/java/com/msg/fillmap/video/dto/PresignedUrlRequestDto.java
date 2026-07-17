package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "S3 업로드용 presigned URL 발급 요청")
public record PresignedUrlRequestDto(
	@Schema(description = "영상 파일 확장자 (점 없이)", example = "mp4")
	@NotBlank String extension,

	@Schema(description = "영상 MIME 타입", example = "video/mp4")
	@NotBlank String contentType,

	@Schema(description = "업로드할 파일 크기(바이트). 서버 상한 초과 시 거부", example = "10485760")
	@NotNull @Positive Long contentLength
) {
}
