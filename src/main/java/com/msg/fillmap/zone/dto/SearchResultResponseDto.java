package com.msg.fillmap.zone.dto;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.region.repository.RegionSearchProjection;
import com.msg.fillmap.zone.entity.Zone;

/**
 * 장소 검색 결과 한 건 (MSG-234 GET /api/search). zones→regions 2단 폴백의 두 종류를 type 으로 구분한다.
 * ZONE 의 bbox 는 사각형 정수 산술(GridConstants, geospatial 0), REGION 은 native ST_Envelope 결과다(§D6).
 */
@Schema(description = "장소 검색 결과. ZONE(구역) 또는 REGION(행정동 폴백).")
public record SearchResultResponseDto(
	@Schema(description = "결과 종류", example = "ZONE", allowableValues = {"ZONE", "REGION"})
	String type,

	@Schema(description = "히트 이름 (zone.name 또는 region_name)", example = "서면")
	String name,

	@Schema(description = "행정동 코드 (nullable)", example = "2623051000")
	String regionCode,

	@Schema(description = "시군구 코드 — 동명 행정동 구분(REGION), zone 은 null 가능", example = "26230")
	String parentCode,

	@Schema(description = "fitBounds용 bbox 남단 위도", example = "35.1495")
	Double minLat,

	@Schema(description = "fitBounds용 bbox 서단 경도", example = "129.0507")
	Double minLon,

	@Schema(description = "fitBounds용 bbox 북단 위도", example = "35.1603")
	Double maxLat,

	@Schema(description = "fitBounds용 bbox 동단 경도", example = "129.06795")
	Double maxLon
) {

	private static final int PARENT_CODE_LENGTH = 5;

	/**
	 * zone 히트 → 검색 결과. bbox 는 사각형 정수 산술 — SW=min_grid·step, NE=(max_grid+1)·step(GridEncoder.bbox 와 동일
	 * 반열림 경계). parentCode 는 region_code 앞 5자리(RegionGeoJsonReader 와 동일 규칙), region_code 가 없으면 null.
	 */
	public static SearchResultResponseDto ofZone(Zone zone) {
		String regionCode = zone.getRegionCode();
		String parentCode = (regionCode != null && regionCode.length() >= PARENT_CODE_LENGTH)
			? regionCode.substring(0, PARENT_CODE_LENGTH) : null;
		return new SearchResultResponseDto(
			"ZONE", zone.getName(), regionCode, parentCode,
			zone.getMinGridY() * GRID_LAT_STEP,
			zone.getMinGridX() * GRID_LNG_STEP,
			(zone.getMaxGridY() + 1) * GRID_LAT_STEP,
			(zone.getMaxGridX() + 1) * GRID_LNG_STEP);
	}

	/** regions 폴백 히트 → 검색 결과. bbox 는 native ST_Envelope 가 이미 뽑아둔 값을 그대로 싣는다. */
	public static SearchResultResponseDto ofRegion(RegionSearchProjection region) {
		return new SearchResultResponseDto(
			"REGION", region.getRegionName(), region.getRegionCode(), region.getParentCode(),
			region.getMinLat(), region.getMinLon(), region.getMaxLat(), region.getMaxLon());
	}
}
