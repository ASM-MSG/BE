package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 영상 교체 요청 (MSG-71).
 *
 * lat/lon 은 선택이다 — 보내지 않으면 기존 격자를 그대로 쓴다(파일만 교체). 보내면 같은 격자인지
 * 검사하고, 다르면 GRID_MISMATCH 로 거부한다(D3 — 격자 변경은 점령 롤백과 신규 점령이 얽혀 MVP 범위 밖).
 */
@Schema(description = "영상 교체 요청. 파일만 바꾸려면 좌표를 생략한다. 좌표를 보내면 기존과 같은 격자여야 하며 다르면 GRID_MISMATCH로 거부된다.")
public record VideoReplaceRequestDto(
	@Schema(description = "새로 업로드한 영상의 S3 객체 키", example = "videos/2026/07/new-uuid.mp4")
	@NotBlank String s3Key,

	@Schema(description = "위도 (선택). lon과 함께 보내거나 둘 다 생략", example = "37.5665", nullable = true)
	Double lat,

	@Schema(description = "경도 (선택). lat과 함께 보내거나 둘 다 생략", example = "126.9780", nullable = true)
	Double lon,

	@Schema(description = "영상 길이(초). 1~30초", example = "15")
	@NotNull @Min(1) @Max(30) Short durationSec,

	@Schema(description = "촬영 시각", example = "2026-07-17T14:30:00")
	@NotNull LocalDateTime recordedAt
) {

	/** 좌표는 둘 다 있거나 둘 다 없어야 한다 — 하나만 오면 격자를 정할 수 없다. */
	public boolean hasCoordinate() {
		return lat != null && lon != null;
	}

	public boolean hasPartialCoordinate() {
		return (lat == null) != (lon == null);
	}
}
