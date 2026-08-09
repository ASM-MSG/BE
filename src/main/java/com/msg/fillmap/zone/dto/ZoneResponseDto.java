package com.msg.fillmap.zone.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.zone.entity.Zone;

/**
 * 구역 목록 응답 (MSG-234 GET /api/zones). FE 가 캐시해 gridId 로 표시명을 로컬 산술하고 구역 오버레이에 쓴다(§D3).
 * 식별자는 재시딩·환경 무관 안정 참조인 zoneKey 를 노출한다(DB 생성 id 는 환경마다 달라 노출하지 않음, §D5).
 */
@Schema(description = "격자 표시명 계산용 구역(zone). 정수 사각형 + 이름 + 소속 행정동.",
	requiredProperties = {"zoneKey", "name", "minGridY", "maxGridY", "minGridX", "maxGridX", "priority", "regionCode"})
public record ZoneResponseDto(
	@Schema(description = "안정 식별자 slug (zones.zone_key) — 클라이언트 참조·타이브레이크 기준", example = "seomyeon")
	String zoneKey,

	@Schema(description = "구역명 (zones.name)", example = "서면")
	String name,

	@Schema(description = "소속 행정동 코드 (zones.region_code, nullable)", example = "2623051000",
		nullable = true)
	String regionCode,

	@Schema(description = "사각형 남단 행 (zones.min_grid_y)", example = "16850")
	Integer minGridY,

	@Schema(description = "사각형 북단 행 = A행 (zones.max_grid_y)", example = "16866")
	Integer maxGridY,

	@Schema(description = "사각형 서단 열 = 1열 (zones.min_grid_x)", example = "11414")
	Integer minGridX,

	@Schema(description = "사각형 동단 열 (zones.max_grid_x)", example = "11424")
	Integer maxGridX,

	@Schema(description = "겹침 결정성 우선순위 (zones.priority)", example = "0")
	Integer priority
) {

	public static ZoneResponseDto from(Zone zone) {
		return new ZoneResponseDto(
			zone.getZoneKey(), zone.getName(), zone.getRegionCode(),
			zone.getMinGridY(), zone.getMaxGridY(), zone.getMinGridX(), zone.getMaxGridX(), zone.getPriority());
	}
}
