package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 업로드 완료 후 메타데이터 저장 요청 (POST /api/videos).
 * s3Key 는 MSG-64 presigned 발급 응답값, 좌표는 촬영/영상 메타데이터 좌표.
 */
@Schema(description = "S3 업로드 완료 후 영상 메타데이터 저장 요청")
public record VideoUploadRequestDto(

	@Schema(description = "presigned 발급 때 받은 S3 객체 키", example = "videos/2026/07/uuid.mp4")
	@NotBlank
	String s3Key,

	@Schema(description = "촬영 위치 위도 (격자 매핑에 사용)", example = "37.5665")
	@NotNull
	Double lat,

	@Schema(description = "촬영 위치 경도 (격자 매핑에 사용)", example = "126.9780")
	@NotNull
	Double lon,

	@Schema(description = "영상 길이(초). 1~30초", example = "15")
	@NotNull
	@Min(1)
	@Max(30)
	Short durationSec,

	@Schema(description = "촬영 시각", example = "2026-07-17T14:30:00")
	@NotNull
	LocalDateTime recordedAt,

	// @NotBlank 를 붙이지 않는다 — null 이 유효값(기본 PUBLIC)이다. 빈 문자열·오타는 서비스 파싱이 3420 으로 거른다 (MSG-204).
	@Schema(description = "공개범위. PUBLIC, PRIVATE, FRIENDS 중 하나. 생략 시 PUBLIC", example = "PUBLIC")
	String visibility
) {
}
