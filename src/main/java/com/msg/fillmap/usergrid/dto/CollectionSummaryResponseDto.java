package com.msg.fillmap.usergrid.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.usergrid.service.CollectionSummaryView;

/**
 * 개인 도감 요약 응답 (MSG-152). 지도 홈 상단 카드·도감 화면의 한 줄 요약에 쓰인다.
 * 점령 0건 사용자도 에러 없이 세 필드가 모두 0으로 채워진다.
 */
@Schema(description = "개인 도감 요약 — 점령한 격자 수·올린 영상 총합·방문한 행정동 수.",
	requiredProperties = {"totalGridCount", "totalVideoCount", "visitedRegionCount"})
public record CollectionSummaryResponseDto(
	@Schema(description = "내가 점령한 격자 수 (도감 크기)", example = "15")
	Integer totalGridCount,

	@Schema(description = "내가 올린 영상 총합 (활성 영상만)", example = "42")
	Long totalVideoCount,

	@Schema(description = "내가 방문한 서로 다른 행정동 수", example = "6")
	Integer visitedRegionCount
) {

	public static CollectionSummaryResponseDto from(CollectionSummaryView view) {
		return new CollectionSummaryResponseDto(
			view.totalGridCount(),
			view.totalVideoCount(),
			view.visitedRegionCount()
		);
	}
}
