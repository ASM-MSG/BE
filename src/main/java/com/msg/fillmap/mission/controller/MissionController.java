package com.msg.fillmap.mission.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 미션 오버레이 — 지도가 뜰 때 지금 활성인 미션 전부를 유형별 렌더 shape 로 한 번에 내려준다(MSG-222).
 * 전역 데이터라 principal 을 읽지 않지만 인증은 필수다(SecurityConfig anyRequest().authenticated(),
 * grid /cover·reverse-geocode 와 동일한 로그인 게이트).
 */
@Tag(name = "미션 (Missions)", description = "지도 오버레이용 활성 미션 목록 조회 API.")
@RestController
@RequiredArgsConstructor
public class MissionController {

	private final MissionQueryService missionQueryService;

	@Operation(
		summary = "활성 미션 목록 조회",
		description = "지금 활성인 미션 전부를 유형별 렌더 shape(코스=PATH·구역=REGION·축제=BOX·테마/지속=CELLS)로 "
			+ "반환한다. bbox 없이 전역 목록이며 1h 전역 캐시로 재계산을 흡수한다. 시드 전이거나 활성 미션이 없으면 "
			+ "빈 배열이다(404 아님)."
	)
	@GetMapping("/api/missions/active")
	public SuccessResponse<List<MissionResponseDto>> getActiveMissions() {
		return SuccessResponse.of(missionQueryService.getActiveMissions());
	}
}
