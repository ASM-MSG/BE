package com.msg.fillmap.region.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.region.service.RegionView;

/**
 * 역지오코딩 응답 (MSG-93). 포함 행정동이 없으면 body 자체가 null 이다(§D3). boundary_geom(경계 폴리곤)은
 * 무겁고 검색 UX 에 불필요해 넣지 않는다(YAGNI) — 경계가 필요한 화면이 생기면 GeoJSON opt-in 을 추가한다.
 */
@Schema(description = "좌표를 포함하는 행정동. 포함 행정동이 없으면(바다·국외) body 가 null 이다.")
public record RegionResponseDto(
	@Schema(description = "행정동 코드 (regions.region_code = adm_cd2)", example = "1168051500")
	String regionCode,

	@Schema(description = "행정동 이름 (regions.region_name = adm_nm)", example = "서울특별시 강남구 역삼1동")
	String regionName,

	@Schema(description = "상위 시군구 코드 (regions.parent_code)", example = "11680")
	String parentCode
) {

	public static RegionResponseDto from(RegionView view) {
		return new RegionResponseDto(view.regionCode(), view.regionName(), view.parentCode());
	}
}
