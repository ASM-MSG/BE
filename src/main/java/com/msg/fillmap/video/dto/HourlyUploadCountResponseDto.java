package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 격자 시간대 분포의 한 구간 (MSG-372). hour 는 KST 기준 시(0~23), count 는 그 시간대에 업로드된
 * 전역 공개 영상 수다. 업로드가 없는 구간도 count 0 으로 항상 실린다.
 */
@Schema(description = "시간대 구간 하나의 업로드 수", requiredProperties = {"hour", "count"})
public record HourlyUploadCountResponseDto(
	@Schema(description = "KST 기준 시 (0~23)", example = "18")
	int hour,

	@Schema(description = "그 시간대의 전역 공개 영상 수. 업로드가 없으면 0", example = "3")
	long count
) {
}
