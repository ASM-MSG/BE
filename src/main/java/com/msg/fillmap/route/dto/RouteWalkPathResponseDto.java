package com.msg.fillmap.route.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 보행 경로 조회 응답 (MSG-483 §API) — 요청과 같은 개수, 같은 순서다. TMap 실패·형태 위반·일 한도
 * 소진은 에러가 아니라 해당 세그먼트 {@code resolved: false} 다(부분 실패 허용, FR-ROUTE-17 — 부분과
 * 전체 실패를 한 형태로 수렴해 FE 폴백 분기가 하나다). 시각 필드가 없어 시각 컨벤션(MSG-376) 검사
 * 대상이 아니다.
 */
@Schema(description = "보행 경로 조회 응답 — 요청과 같은 개수, 같은 순서", requiredProperties = {"segments"})
public record RouteWalkPathResponseDto(

	@Schema(description = "세그먼트별 보행 경로 결과")
	List<WalkSegmentDto> segments
) {

	/**
	 * 세그먼트 하나의 결과 — 실패면 FE 는 그 세그먼트를 직선과 직선거리 안내로 유지한다 (FR-5).
	 * Jackson 기본 직렬화라 null 값도 키는 항상 나간다 — 전 필드 requiredProperties 에, null 가능 필드
	 * (path·distanceMeters)는 nullable 을 함께 단다 (zoneName 선례, ResponseSchemaNullabilityTest 강제).
	 */
	@Schema(description = "세그먼트 보행 경로", requiredProperties = {"resolved", "path", "distanceMeters"})
	public record WalkSegmentDto(

		@Schema(description = "보행 경로 확보 여부 — false 면 직선 폴백")
		boolean resolved,

		@Schema(description = "보행로를 따르는 좌표열 (위도-경도 순). 실패 시 null", nullable = true)
		List<PathPointDto> path,

		@Schema(description = "실제 걷는 거리 (TMap totalDistance, 미터). 실패 시 null", nullable = true)
		Integer distanceMeters
	) {
	}

	@Schema(description = "보행로 좌표 (WGS84)", requiredProperties = {"lat", "lng"})
	public record PathPointDto(

		@Schema(description = "위도", example = "35.1587")
		double lat,

		@Schema(description = "경도", example = "129.1604")
		double lng
	) {
	}
}
