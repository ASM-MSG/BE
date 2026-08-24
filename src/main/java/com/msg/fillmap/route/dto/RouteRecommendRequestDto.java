package com.msg.fillmap.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * AI 경로 추천 요청 (MSG-457 §API). 필드 단위 위반(text 길이·필수 누락·origin 범위)은 @Valid 공통 400
 * (PlaceSearchController 선례 — 신규 코드 없음)이고, viewport 의 좌표 의미 검증(min ≥ max·범위 밖·
 * 비유한값 14400, 한 변 0.5도 초과 14401)은 서비스가 parse 호출 전에 한다 — AI 의 422 로 새면 의미가
 * 다른 14502 가 되기 때문이다.
 */
@Schema(description = "AI 경로 추천 요청")
public record RouteRecommendRequestDto(

	@Schema(description = "하고 싶은 일 자연어 한 문장 (trim 후 1~500자)",
		example = "부산역 내려서 해운대에서 밥 먹고 축제도 보고 싶어")
	@NotBlank
	@Size(max = 500)
	String text,

	@Schema(description = "지금 보고 있는 지도 범위 (WGS84 사각형)")
	@NotNull
	@Valid
	ViewportDto viewport,

	@Schema(description = "출발 지점 좌표 (선택). 있으면 동선이 여기서 시작한다")
	@Valid
	OriginDto origin
) {

	/** 스펙 "trim 후 1~500자" — 여기서 정규화해야 @NotBlank·@Size 가 trim 후 값을 잰다 (공백 딸린 유효 문장 오거부 방지). */
	public RouteRecommendRequestDto {
		text = text == null ? null : text.trim();
	}

	/** 좌표 의미 검증(14400·14401)은 서비스 몫이라 여기서는 누락만 잡는다 — 원시 double 이면 누락이 0.0 이 된다. */
	@Schema(description = "WGS84 뷰포트 사각형")
	public record ViewportDto(

		@Schema(description = "남서 위도", example = "35.05") @NotNull Double minLat,
		@Schema(description = "남서 경도", example = "128.95") @NotNull Double minLng,
		@Schema(description = "북동 위도", example = "35.25") @NotNull Double maxLat,
		@Schema(description = "북동 경도", example = "129.20") @NotNull Double maxLng
	) {
	}

	/** 출발 지점 (FR-ROUTE-11). 범위 검증은 필드 단위라 text 와 같은 공통 400 경로다. */
	@Schema(description = "출발 지점 좌표")
	public record OriginDto(

		@Schema(description = "위도", example = "35.115")
		@NotNull
		@DecimalMin("-90.0")
		@DecimalMax("90.0")
		Double lat,

		@Schema(description = "경도", example = "129.042")
		@NotNull
		@DecimalMin("-180.0")
		@DecimalMax("180.0")
		Double lng
	) {
	}
}
