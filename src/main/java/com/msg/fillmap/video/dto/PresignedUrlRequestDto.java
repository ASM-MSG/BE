package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

@Schema(description = "S3 업로드용 presigned URL 발급 요청")
public record PresignedUrlRequestDto(
	@Schema(description = "영상 파일 확장자 (점 없이)", example = "mp4")
	@NotBlank String extension,

	@Schema(description = "영상 MIME 타입", example = "video/mp4")
	@NotBlank String contentType,

	@Schema(description = "업로드할 파일 크기(바이트). 서버 상한 초과 시 거부", example = "10485760")
	@NotNull @Positive Long contentLength,

	// String + @Pattern: enum 필드로 받으면 오타가 역직렬화 실패 → 500 이 된다 (visibility 의 MSG-162 선례).
	// null 은 검증을 통과해 기존(UPLOAD) 동작 그대로다 — 기존 FE 호출 하위 호환 (MSG-351).
	@Schema(description = "발급 용도. 미지정(null)은 UPLOAD 와 동일. 하이라이트 선분석 원본은 HIGHLIGHT_PREVIEW 로 "
		+ "발급받아 전용 크기 상한(기본 2GiB)을 적용받는다", example = "HIGHLIGHT_PREVIEW")
	@Pattern(regexp = "UPLOAD|HIGHLIGHT_PREVIEW", message = "purpose 는 UPLOAD 또는 HIGHLIGHT_PREVIEW 여야 합니다")
	String purpose
) {
}
