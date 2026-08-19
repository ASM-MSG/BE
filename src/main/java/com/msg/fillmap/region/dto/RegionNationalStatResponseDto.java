package com.msg.fillmap.region.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.region.service.RegionNationalStatView;

/**
 * 전국 탐험률 재료 응답 (MSG-406, 도감·프로필 헤더의 "전체 지도 N% 탐험"). 분자/분모 원값 2개만 담고
 * 비율 필드는 두지 않는다 — 소수 2자리 반올림으로는 "0.012%" 를 표현할 수 없어 나눗셈과 표시 자릿수,
 * 100 상한을 화면이 정한다(§D-2·D-3). 면적 근사 분모 탓에 드물게 분자가 분모를 넘을 수 있다.
 */
@Schema(description = "내 전국 탐험률 재료. 점령한 격자 수(분자)와 전국 격자 총수(분모)의 원값.",
	requiredProperties = {"collectedCount", "totalCount"})
public record RegionNationalStatResponseDto(
	@Schema(description = "내가 점령(수집)한 격자 수의 전국 합. 수집이 없으면 0", example = "1223")
	Long collectedCount,

	@Schema(description = "전국 격자 총수(분모). 0 이면 기준 데이터 미적재 상태라 화면은 비율을 그리지 않는다",
		example = "10193482")
	Long totalCount
) {

	public static RegionNationalStatResponseDto from(RegionNationalStatView view) {
		return new RegionNationalStatResponseDto(view.collectedCount(), view.totalCount());
	}
}
