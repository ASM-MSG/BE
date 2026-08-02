package com.msg.fillmap.mission.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonRawValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 미션 유형별 렌더 shape (MSG-222 §API 명세). 판별자는 상위 MissionResponseDto.type 이라 wire 에 @type 을 붙이지
 * 않는다(§설계 D2) — 각 record 는 자기 필드만 직렬화한다. 유형별 전략 "클래스"가 아니라 유형별 "DTO record"라
 * 설계검토의 "전략 클래스 금지"에 위배되지 않는다(로직은 서비스 단일 분기, §도메인 3).
 */
@Schema(description = "미션 유형별 렌더 shape (상위 type 으로 판별). PATH·BOX·CELLS·REGION 중 하나.")
public sealed interface MissionShape
	permits MissionShape.PathShape, MissionShape.BoxShape, MissionShape.CellsShape, MissionShape.RegionShape {

	/** 좌표 한 점 (경계 폴리곤 꼭짓점). */
	@Schema(description = "좌표 한 점", requiredProperties = {"lat", "lon"})
	record LatLng(double lat, double lon) {
	}

	/** 코스 포토스팟 마커 (격자 중심점 + gridId + seq). */
	@Schema(description = "코스 포토스팟 마커", requiredProperties = {"gridId", "lat", "lon"})
	record Spot(String gridId, double lat, double lon, Integer seq) {
	}

	/** 격자 중심점 (gridId 포함). */
	@Schema(description = "격자 중심점", requiredProperties = {"gridId", "lat", "lon"})
	record Cell(String gridId, double lat, double lon) {
	}

	/**
	 * PATH (COURSE) — 코스 라인 + 포토스팟 마커. line 은 missions.path GeoJSON LineString 원문을 raw JSON 으로
	 * 재발행한다(@JsonRawValue, §설계 D7) — 따옴표 escape 없이 JSON 객체로 나간다. spots 는 seq 오름차순.
	 */
	@Schema(description = "코스(COURSE) — GeoJSON LineString + seq순 포토스팟 마커",
		requiredProperties = {"spots"})
	record PathShape(
		@Schema(description = "코스 라인 GeoJSON LineString 원문", type = "object")
		@JsonRawValue String line,
		List<Spot> spots
	) implements MissionShape {
	}

	/**
	 * BOX (EVENT) — mission_grids 격자 집합을 감싸는 경계 사각형 5점 닫힌 링(남서→남동→북동→북서→남서).
	 * 축제는 큰 사각형, 단일 격자 팝업은 한 셀 사각형(≈마커)으로 자연 정합.
	 */
	@Schema(description = "이벤트(EVENT) — 격자 집합을 감싸는 경계 사각형", requiredProperties = {"polygon"})
	record BoxShape(List<LatLng> polygon) implements MissionShape {
	}

	/** CELLS (THEME·CONTINUOUS) — 각 격자 중심점 배열(seq 무의미). */
	@Schema(description = "테마·지속(THEME·CONTINUOUS) — 각 격자 중심점", requiredProperties = {"cells"})
	record CellsShape(List<Cell> cells) implements MissionShape {
	}

	/** REGION (AREA) — region_code 만(경계 지오메트리 미반환, §설계 D5. FE 가 region API 로 별도 조회). */
	@Schema(description = "구역(AREA) — region_code 만(경계는 region API 로 별도 조회)")
	record RegionShape(String regionCode) implements MissionShape {
	}
}
