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
 * 구역 목록 API (MSG-234). 표시명 계산용 zone 데이터를 FE 에 한 번 내려준다(§D3, FE-local 계산).
 * 3-layer 얇게 — 서비스 호출 + SuccessResponse 변환만. 지도 화면 뒤 조회라 로그인 필요(SecurityConfig anyRequest).
 */
@Tag(name = "구역 (Zone)", description = "격자 표시명(\"서면 A-14\") 계산용 구역 데이터. FE 가 캐시해 로컬 명명·오버레이에 쓴다.")
@RestController
@RequestMapping("/api/zones")
@RequiredArgsConstructor
public class ZoneController {

	private final ZoneQueryService zoneQueryService;

	@Operation(
		summary = "구역 목록 조회",
		description = "전체 구역(zone) 목록을 반환한다. FE 가 캐시해 gridId 로 표시명을 로컬 산술하고 구역 오버레이에 쓴다. "
			+ "시딩 전이면 빈 배열(전 시스템이 행정동 폴백으로 동작)."
	)
	@GetMapping
	public SuccessResponse<List<ZoneResponseDto>> getZones() {
		return SuccessResponse.of(zoneQueryService.getZones());
	}
}
