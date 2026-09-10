package com.msg.fillmap.zone.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.zone.dto.ZoneResponseDto;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * 구역 목록 API (MSG-234). 구역의 이름과 격자 사각형 범위를 FE 에 한 번 내려준다.
 * 표시명 계산은 서버가 한다(MSG-341) — 격자를 담는 응답이 zoneName·zoneCell 을 직접 싣고, 구역 밖이면
 * regionName(행정동)이 폴백이다(MSG-349). 그래서 이 API 의 용도는 검색바 구역 이동(MSG-234 §D6)과
 * 구역 범위 오버레이 두 가지다.
 * 3-layer 얇게 — 서비스 호출 + SuccessResponse 변환만. 위 두 용도가 다 사용자 무관이라 비로그인 열람을
 * 허용한다(SecurityConfig permitAll, MSG-469).
 */
@Tag(name = "구역 (Zone)", description = "구역(\"서면\" 등)의 이름과 격자 사각형 범위. "
	+ "검색바에서 구역으로 지도를 옮기거나 구역 범위를 오버레이로 그릴 때 쓴다 — "
	+ "격자 표시명(\"서면 A-14\")은 서버가 계산해 격자 응답에 함께 싣는다.")
@RestController
@RequestMapping("/api/zones")
@RequiredArgsConstructor
public class ZoneController {

	private final ZoneQueryService zoneQueryService;

	@Operation(
		summary = "구역 목록 조회",
		description = "전체 구역(zone) 목록을 반환한다. 검색바에서 구역을 골라 지도를 옮기거나 구역 범위를 "
			+ "오버레이로 그릴 때 쓴다 — 표시명은 격자 응답의 zoneName·zoneCell 을 그대로 조립하면 되므로 "
			+ "이 목록으로 이름을 계산할 필요가 없다. 시딩 전이면 빈 배열(전 시스템이 행정동 폴백으로 동작)."
	)
	@GetMapping
	public SuccessResponse<List<ZoneResponseDto>> getZones() {
		return SuccessResponse.of(zoneQueryService.getZones());
	}
}
