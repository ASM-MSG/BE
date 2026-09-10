package com.msg.fillmap.video.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행정동 격자 카드 + 전체 기준 카운트 래퍼 (MSG-238 §D2, 90 래퍼+메타 계열). 지도 홈 패널
 * (sort=LATEST&limit=20 — MSG-387)과 전체 보기(limit 생략)가 같은 응답을 쓴다 — 카운트는 limit 무관
 * 전체 기준이라 카드 집합과 항상 같은 정의다(§D1). 단 전역 공개 콘텐츠를 센 값이라 패널 헤더
 * ("이 지역 격자 N개 · 영상 M개")에는 쓰지 않는다 — 헤더는 집계 응답 currentRegion(내 것, MSG-374) 몫.
 * 미존재/무콘텐츠 regionCode 는 에러가 아니라 0·빈 배열이다(§D2).
 */
@Schema(description = "행정동 격자 카드 리스트 + 헤더 카운트",
	requiredProperties = {"regionCode", "gridCount", "videoCount", "grids", "regionName"})
public record RegionExploreResponseDto(
	@Schema(description = "행정동 코드 (요청 에코)", example = "2644056000")
	String regionCode,

	@Schema(description = "행정동 이름 — 미존재 코드면 null", example = "부산광역시 부산진구 부전2동", nullable = true)
	String regionName,

	@Schema(description = "게이트 통과 영상 ≥1 격자 수 — \"이 지역 격자 N개\"", example = "5")
	Integer gridCount,

	@Schema(description = "게이트 통과 영상 총수 — \"영상 M개\"", example = "355")
	Long videoCount,

	@Schema(description = "격자 카드 (정렬·limit 적용 후). 없으면 빈 배열")
	List<ExploreGridResponseDto> grids
) {
}
