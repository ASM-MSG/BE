package com.msg.fillmap.event.submission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import com.msg.fillmap.event.submission.entity.EventSubmissionAreaRect;

/**
 * 위치 영역 사각형 하나 (MSG-498). 격자 인덱스 네 정수이고 형식은 seed/events.json 과 같다.
 * 제출 요청과 상세 응답이 같은 타입을 쓴다 — 상세의 사각형이 "제출 원본 그대로"라 형태가 같아야 재제출
 * 폼 프리필이 변환 없이 성립하기 때문이다.
 */
@Schema(description = "위치 영역 사각형 (격자 인덱스). 위치 하나의 합집합은 최대 81칸이다.",
	requiredProperties = {"minGridY", "maxGridY", "minGridX", "maxGridX"})
public record EventSubmissionAreaRectDto(
	@Schema(description = "격자 행 인덱스 최소", example = "16859")
	@NotNull Integer minGridY,

	@Schema(description = "격자 행 인덱스 최대", example = "16861")
	@NotNull Integer maxGridY,

	@Schema(description = "격자 열 인덱스 최소", example = "11509")
	@NotNull Integer minGridX,

	@Schema(description = "격자 열 인덱스 최대", example = "11515")
	@NotNull Integer maxGridX
) {

	public static EventSubmissionAreaRectDto from(EventSubmissionAreaRect rect) {
		return new EventSubmissionAreaRectDto(rect.getMinGridY(), rect.getMaxGridY(),
			rect.getMinGridX(), rect.getMaxGridX());
	}

	public EventSubmissionAreaRect toEntity() {
		return new EventSubmissionAreaRect(minGridY, maxGridY, minGridX, maxGridX);
	}
}
