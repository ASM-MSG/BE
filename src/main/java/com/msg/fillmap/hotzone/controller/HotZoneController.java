package com.msg.fillmap.hotzone.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.hotzone.dto.HotZoneListResponseDto;
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
}
