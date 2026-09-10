package com.msg.fillmap.usergrid.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.usergrid.service.CollectionSummaryView;

/**
 * 개인 도감 요약 응답 (MSG-152 · 스트릭/뱃지 확장 MSG-362). 지도 홈 상단 카드·도감 화면의 한 줄 요약에
 * 쓰인다. 업로드 경험 0 사용자도 에러 없이 여섯 필드가 모두 0 으로 채워진다. 친구 프로필의 summary 가
 * 이 DTO 를 중첩 재사용하므로 친구에게도 같은 지표가 실린다.
 */
@Schema(description = "개인 도감 요약 — 점령한 격자 수·올린 영상 총합·방문한 행정동 수·현재/최장 스트릭·획득 뱃지 수.",
	requiredProperties = {"totalGridCount", "totalVideoCount", "visitedRegionCount",
		"currentStreak", "maxStreak", "badgeCount"})
public record CollectionSummaryResponseDto(
	@Schema(description = "내가 점령한 격자 수 (도감 크기)", example = "15")
	Integer totalGridCount,

	@Schema(description = "내가 올린 영상 총합 (활성 영상만)", example = "42")
	Long totalVideoCount,

	@Schema(description = "내가 방문한 서로 다른 행정동 수", example = "6")
	Integer visitedRegionCount,

	@Schema(description = "현재 스트릭 (연속 업로드 일수). 마지막 기록이 KST 그제 이전이면 끊긴 것으로 보고 0",
		example = "12")
	Integer currentStreak,

	@Schema(description = "최장 스트릭. 끊겨도 유지되는 역대 최고 기록", example = "21")
	Integer maxStreak,

	@Schema(description = "획득한 뱃지 수", example = "7")
	Integer badgeCount
) {

	public static CollectionSummaryResponseDto from(CollectionSummaryView view) {
		return new CollectionSummaryResponseDto(
			view.totalGridCount(),
			view.totalVideoCount(),
			view.visitedRegionCount(),
			view.currentStreak(),
			view.maxStreak(),
			view.badgeCount()
		);
	}
}
