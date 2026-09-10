package com.msg.fillmap.route.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 보행 경로 조회 요청 (MSG-483 §API). 검증은 서비스에서 한 코드로 수렴한다 — 목록이 없거나 비었거나
 * 9개 이상, 원소 null, 좌표가 한국 서비스 범위(위도 33~39·경도 124~132, SRS 2.4) 밖이면 전부 14402 다.
 * 그래서 필드 검증 애너테이션이 없다(공통 400 으로 갈리면 FE 분기가 둘이 된다). 시각 필드가 없어 시각
 * 컨벤션(MSG-376) 검사 대상이 아니다.
 */
@Schema(description = "보행 경로 조회 요청")
public record RouteWalkPathRequestDto(

	@Schema(description = "추천 응답의 이웃 좌표쌍 목록 (1~8개 — 지점 상한 8이라 세그먼트 최대 7개에 출발지 구간 1개)")
	List<SegmentDto> segments
) {

	/**
	 * 이웃 두 지점 사이 구간. 좌표는 박싱 Double — 원시 double 이면 Jackson 3 이 필드 누락·null 을
	 * 역직렬화 실패로 던져 공통 400 으로 새고 14402 단일 수렴이 깨진다(2026-08-26 실측). 누락·null 은
	 * null 로 들어와 서비스 검증이 14402 로 거른다(범위 비교가 NaN·무한대까지 함께 걸러낸다, §API 요청 표).
	 */
	@Schema(description = "이웃 두 지점 사이 구간 (WGS84)")
	public record SegmentDto(

		@Schema(description = "출발 위도", example = "35.1587")
		Double startLat,

		@Schema(description = "출발 경도", example = "129.1604")
		Double startLng,

		@Schema(description = "도착 위도", example = "35.1631")
		Double endLat,

		@Schema(description = "도착 경도", example = "129.1635")
		Double endLng
	) {
	}
}
