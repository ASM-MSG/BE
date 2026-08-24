package com.msg.fillmap.hotzone.controller;

import java.util.List;
import java.util.Locale;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.hotzone.dto.HotZoneListResponseDto;
import com.msg.fillmap.hotzone.dto.HotZoneRegionAggregateResponseDto;
import com.msg.fillmap.hotzone.exception.HotZoneErrorCode;
import com.msg.fillmap.hotzone.service.HotZoneService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 핫구역 조회 API (MSG-184). 3-layer 얇게 — 파싱 + 서비스 호출 + SuccessResponse 변환만.
 * userId 미사용(개인화 없음, D5) — @AuthenticationPrincipal 을 받지 않고 인증은 공통 정책에 맡긴다.
 * 파라미터 누락은 @RequestParam required 기본(true)으로 전역 400 핸들러(MSG-167 매핑)가 처리한다.
 */
@Tag(name = "핫구역 (HotZone)", description = "최근 48시간 방문(업로드) 신호 상위 격자 조회 API — 개인화 없는 공용 목록.")
@RestController
@RequestMapping("/api/hotzones")
@RequiredArgsConstructor
public class HotZoneController {

	private final HotZoneService hotZoneService;

	@Operation(
		summary = "뷰포트 내 핫구역 조회",
		description = "지도 화면 bbox(남서~북동 좌표) 안의 핫구역을 핫스코어 내림차순으로 반환한다. "
			+ "전국 상위 K(50)·최소 임계(3) 판정 후 뷰포트 필터 — 없으면 빈 목록이다.\n\n"
			+ "항목마다 표시 이름 재료가 함께 온다: zoneName이 null이면 regionName(행정동)이 표시 이름이다"
			+ "(폴백에는 칸 번호를 붙이지 않는다). 이름 때문에 마커마다 단건 조회를 돌릴 필요가 없다."
	)
	@GetMapping
	public SuccessResponse<HotZoneListResponseDto> getHotZones(
		@Parameter(description = "남서 모서리 위도", example = "37.50")
		@RequestParam double swLat,
		@Parameter(description = "남서 모서리 경도", example = "127.00")
		@RequestParam double swLng,
		@Parameter(description = "북동 모서리 위도", example = "37.55")
		@RequestParam double neLat,
		@Parameter(description = "북동 모서리 경도", example = "127.05")
		@RequestParam double neLng
	) {
		ViewportBounds bounds = new ViewportBounds(swLat, swLng, neLat, neLng);
		return SuccessResponse.of(HotZoneListResponseDto.from(hotZoneService.getHotZones(bounds)));
	}

	@Operation(
		summary = "뷰포트 내 핫구역 행정 단위 집계 조회",
		description = "축소 화면용 — 뷰포트 안 핫구역을 행정 단위(동·구·시)로 묶어 지역 이름과 핫 격자 수로 "
			+ "반환한다. 묶음 대상은 개별 조회(GET /api/hotzones)와 완전히 같은 판정 집합이라 두 화면을 "
			+ "갈아타도 세는 대상이 달라지지 않는다.\n\n"
			+ "항목마다 gridIds 가 함께 온다 — 묶음 마커를 눌러 줌인한 뒤 개별 조회 결과와 교집합으로 목록을 "
			+ "좁히는 재료다. count 는 핫 격자 수이고 핫스코어 합산이 아니다. 행정동이 판정되지 않은 격자는 "
			+ "제외가 아니라 regionCode·name 이 null 인 항목 하나로 묶여 마지막에 온다. 범위 안에 핫 격자가 "
			+ "없으면 빈 배열이다.\n\n"
			+ "bbox span 상한은 단위별로 다르다(DONG 1도, SIGUNGU 4도, SIDO 10도 — 위도·경도 각 변에 따로 적용, "
			+ "정확히 상한값은 허용). 초과 시 400 + developCode 8401, 좌표가 WGS84 범위를 벗어나거나 bbox 가 "
			+ "누락·뒤집히면 8400, unit 이 없거나 미지원 값이면 8405 다. 응답에 사용자별 값은 없다."
	)
	@GetMapping("/aggregation")
	public SuccessResponse<List<HotZoneRegionAggregateResponseDto>> getHotZoneAggregates(
		@Parameter(description = "집계 단위 — DONG(동), SIGUNGU(시군구), SIDO(시도). 대소문자 무관",
			required = true, example = "SIGUNGU")
		@RequestParam(required = false) String unit,
		@Parameter(description = "남서 모서리 위도", required = true, example = "35.10")
		@RequestParam(required = false) Double swLat,
		@Parameter(description = "남서 모서리 경도", required = true, example = "128.90")
		@RequestParam(required = false) Double swLng,
		@Parameter(description = "북동 모서리 위도", required = true, example = "35.30")
		@RequestParam(required = false) Double neLat,
		@Parameter(description = "북동 모서리 경도", required = true, example = "129.20")
		@RequestParam(required = false) Double neLng
	) {
		// 검증 순서가 곧 응답 코드다 (§API 명세) — bbox 누락 8400 → unit 8405 →
		// 좌표 정의역·뒤집힘 8400 → span 상한 8401(서비스 validateBounds).
		ViewportBounds bounds = toBounds(swLat, swLng, neLat, neLng);
		return SuccessResponse.of(hotZoneService.getHotZoneAggregates(bounds, toUnit(unit)));
	}

	/**
	 * bbox 는 required = false 로 받아 여기서 검증한다 — Spring 의 필수 파라미터 예외는 도메인 developCode 를
	 * 못 싣기 때문이다(@Parameter(required = true) 가 문서 쪽 계약을 지킨다, MissionController.toBounds 동일 패턴).
	 * 위 개별 조회는 종전대로 @RequestParam 필수 기본이다 — 기존 계약을 바꾸지 않는다 (MSG-466 D5).
	 */
	private ViewportBounds toBounds(Double swLat, Double swLng, Double neLat, Double neLng) {
		if (swLat == null || swLng == null || neLat == null || neLng == null) {
			throw new ApiException(HotZoneErrorCode.INVALID_VIEWPORT);
		}
		return new ViewportBounds(swLat, swLng, neLat, neLng);
	}

	/** unit 도 같은 이유로 required = false 로 받는다 — 누락과 미지원 값을 8405 하나로 수렴시킨다. */
	private RegionUnit toUnit(String unit) {
		if (unit == null) {
			throw new ApiException(HotZoneErrorCode.INVALID_AGGREGATION_UNIT);
		}
		try {
			return RegionUnit.valueOf(unit.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(HotZoneErrorCode.INVALID_AGGREGATION_UNIT, e);
		}
	}
}
