package com.msg.fillmap.grid.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.grid.service.RegionAggregateView;

/**
 * 뷰포트 집계 항목 (MSG-356) — 축소한 지도에 찍을 마커 하나. 이름·대표 좌표·격자 수에 마커 식별 키를 더한다.
 * 이름은 전국에서 중복될 수 있어(여러 도시의 "중앙동") 키가 따로 필요하고, 팬 이동 간 마커 재사용에도 쓴다.
 */
@Schema(description = "행정 단위로 묶어 센 점령 격자 집계 한 항목",
	requiredProperties = {"regionCode", "name", "lat", "lng", "count"})
public record RegionAggregateResponseDto(
	@Schema(description = "묶음 키 — 행정동 코드를 단위 길이로 자른 접두(동 10자리, 구 5자리, 시 2자리). "
		+ "행정동이 판정되지 않은 격자 묶음만 null 이다.",
		example = "2623058000", nullable = true)
	String regionCode,

	@Schema(description = "단위 표시 이름(동 \"부전2동\", 구 \"부산진구\", 시 \"부산광역시\"). "
		+ "\"부산광역시 214\" 를 \"부산 214\" 로 줄이는 표기 축약은 클라이언트 몫이다. "
		+ "행정동이 판정되지 않은 격자 묶음만 null 이다.",
		example = "부전2동", nullable = true)
	String name,

	@Schema(description = "마커 대표 좌표 위도 — 그 묶음에 속한 점령 격자 중심의 평균이다(행정 경계 무게중심이 아니다)",
		example = "35.162")
	double lat,

	@Schema(description = "마커 대표 좌표 경도", example = "129.065")
	double lng,

	@Schema(description = "그 단위 안 점령 격자 수. 항목을 더 묶어 합산해도 같은 뷰포트 개별 조회 총수와 일치한다",
		example = "31")
	int count
) {

	public static RegionAggregateResponseDto from(RegionAggregateView view) {
		return new RegionAggregateResponseDto(view.regionCode(), view.name(), view.lat(), view.lng(), view.count());
	}
}
