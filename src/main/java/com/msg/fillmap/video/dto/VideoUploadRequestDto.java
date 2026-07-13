package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 업로드 완료 후 메타데이터 저장 요청 (POST /api/videos).
 * s3Key 는 MSG-64 presigned 발급 응답값, 좌표는 촬영/영상 메타데이터 좌표.
 */
public record VideoUploadRequestDto(

	@NotBlank
	String s3Key,

	@NotNull
	Double lat,

	@NotNull
	Double lon,

	@NotNull
	@Min(1)
	@Max(30)
	Short durationSec,

	@NotNull
	LocalDateTime recordedAt
) {
}
