package com.msg.fillmap.region.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.region.service.RegionDistrictView;

/**
 * 시군구 목록 항목 응답 (MSG-435, 검색 지역 필터의 "마포구, 격자 2,841"). 격자 수는 그 구의 전체 격자 수라
 * 사용자 무관 값이고, 천 단위 구분 표기는 화면 몫이라 서버는 원값 정수를 준다.
 * parentCode 는 GET /api/regions/stats 의 parentCode 파라미터에 그대로 넣어 이어 쓸 수 있다.
 */
@Schema(description = "시군구 한 건. 이름·식별자·전체 격자 수.",
	requiredProperties = {"parentCode", "name", "gridCount"})
public record RegionDistrictResponseDto(
	@Schema(description = "시군구 식별자(행정동 코드 앞 5자리). /api/regions/stats 의 parentCode 로 그대로 쓴다",
		example = "11680")
	String parentCode,

	@Schema(description = "시군구 이름", example = "강남구")
	String name,

	@Schema(description = "그 시군구의 전체 격자 수(사용자 무관). 0 인 시군구는 목록에 없다", example = "4102")
	Long gridCount
) {

	public static RegionDistrictResponseDto from(RegionDistrictView view) {
		return new RegionDistrictResponseDto(view.parentCode(), view.name(), view.gridCount());
	}
}
