package com.msg.fillmap.video.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 격자 전역 시간대 분포 응답 (MSG-372, 활발한 시간대 차트 재료). hours 는 항상 24개이고 hour 오름차순
 * (0~23) 고정이라 클라이언트가 빈 구간을 채울 필요가 없다. 집계 윈도우는 전체 누적이며, 공개 영상이
 * 없는 격자·존재하지 않는 gridId 도 전 구간 0 인 정상 응답이다.
 */
@Schema(description = "격자 전역 시간대 분포 응답 (KST 24구간)", requiredProperties = {"gridId", "hours"})
public record GridHourlyUploadResponseDto(
	@Schema(description = "격자 ID", example = "19422_9582")
	String gridId,

	@Schema(description = "KST 0시부터 23시까지 24개 구간. 업로드가 없는 구간은 count 0")
	List<HourlyUploadCountResponseDto> hours
) {
}
