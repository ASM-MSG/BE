package com.msg.fillmap.event.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.event.dto.OrgEventListResponseDto;
import com.msg.fillmap.event.service.EventQueryService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 행사 운영자 콘솔의 승인 이벤트 조회 API (MSG-501). 참여 신청(MSG-502)이 부모로 지정할 이벤트를 고르는
 * 모달의 재료 하나다. 인가는 SecurityConfig 의 {@code /api/org/**} matcher 가 전담한다 (MSG-496) —
 * 비로그인 401, USER·ADMIN 403 이라 컨트롤러에는 역할 검사가 없다.
 */
@Tag(name = "행사 운영자 콘솔 (Org)", description = "행사 운영자 전용 조회 API — 승인 이벤트 목록.")
@RestController
@RequiredArgsConstructor
public class OrgEventController {

	private final EventQueryService eventQueryService;

	@Operation(
		summary = "승인 이벤트 목록 조회",
		description = "참여 신청 모달의 재료 — 시·도 칩, 시·도별 건수, 이벤트 목록이다. 담기는 것은 아직 끝나지 "
			+ "않은 회차(예정·진행 중)뿐이고, 종료된 행사(업로드 유예·아카이브)는 참여를 신청해도 열 자리가 "
			+ "없으므로 빠진다. 일반 사용자 조회와 달리 노출 시작 전인 예정 회차도 담긴다 — 심사에 시간이 "
			+ "걸려 행사 운영자는 미리 부모 이벤트를 골라야 한다.\n\n"
			+ "totalCount 와 cityCounts 는 city·name 을 적용하지 않은 전체 기준이라 검색 중에도 칩 건수가 "
			+ "고정이고, events 에만 두 파라미터가 적용된다. cityCounts 는 건수 내림차순·동수는 이름 오름차순, "
			+ "events 는 시작일 오름차순·동시각은 회차 id 오름차순이다.\n\n"
			+ "placeLabel 은 그 회차의 위치 중 표시 순서가 가장 앞선 것의 이름이고, 위치가 없으면 null 이다. "
			+ "존재하지 않는 시·도 값은 실패가 아니라 빈 목록이다."
	)
	@GetMapping("/api/org/events")
	public SuccessResponse<OrgEventListResponseDto> getApprovedEvents(
		@Parameter(description = "시·도 필터 — cityCounts 의 cityName 저장값과 정확 일치", example = "부산")
		@RequestParam(required = false) String city,
		@Parameter(description = "이벤트 이름 검색 — 부분 일치, 대소문자 무시", example = "영화제")
		@RequestParam(required = false) String name
	) {
		return SuccessResponse.of(eventQueryService.getApprovedEvents(city, name));
	}
}
