package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

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
public record VideoReplaceRequestDto(
	@NotBlank String s3Key,
	Double lat,
	Double lon,
	@NotNull @Min(1) @Max(30) Short durationSec,
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
