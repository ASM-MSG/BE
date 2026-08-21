package com.msg.fillmap.event.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.event.dto.GridEventLocationResponseDto;
import com.msg.fillmap.event.service.EventQueryService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 격자 역조회 API (MSG-439 API 4). URL 은 grids 하위지만 구현은 event 패키지다 — 격자를 행사 위치로 해석하는
 * 것은 행사 도메인의 지식이고, grid 도메인 코드는 이 티켓에서 건드리지 않는다.
 */
@Tag(name = "행사 (Events)", description = "지도에서 누른 격자를 행사 위치로 해석하는 역조회 API.")
@RestController
@RequiredArgsConstructor
public class EventLocationController {

	private final EventQueryService eventQueryService;

	@Operation(
		summary = "격자가 속한 행사 위치 조회",
		description = "지도에서 누른 격자가 어느 행사 위치에 속하는지 해석한다. 영상은 위치의 대표 격자 하나에만 "
			+ "저장되므로, 영역 안 아무 격자나 눌러도 같은 위치가 나오는 이 역조회가 위치별 영상 피드로 들어가는 "
			+ "유일한 경로다.\n\n"
			+ "같은 장소에서 행사가 여러 번 열렸으면 회차마다 한 항목씩 배열로 온다. 배열 첫 항목이 화면 진입 "
			+ "기본값이 되도록 진행 중 → 예정 → 업로드 유예 → 아카이브 순으로 정렬하며, 예정끼리는 임박한 순, "
			+ "나머지는 최근 순이다. 아직 노출 기간 전인 예정 회차는 배열에 담기지 않는다.\n\n"
			+ "어떤 행사 위치에도 속하지 않는 격자와 격자 형식이 아닌 문자열은 오류가 아니라 빈 배열이다. "
			+ "비로그인으로도 조회할 수 있다."
	)
	@GetMapping("/api/grids/{gridId}/event-locations")
	public SuccessResponse<List<GridEventLocationResponseDto>> getEventLocationsByGrid(
		@Parameter(description = "격자 id — \"{gridY}_{gridX}\" 포맷", example = "19443_9582")
		@PathVariable String gridId
	) {
		return SuccessResponse.of(eventQueryService.getLocationsByGrid(gridId));
	}
}
