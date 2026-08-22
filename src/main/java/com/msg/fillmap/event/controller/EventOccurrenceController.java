package com.msg.fillmap.event.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.event.dto.EventLocationResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceChipResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceDetailResponseDto;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.service.EventQueryService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 행사 회차 조회 API (MSG-439). 세 경로 모두 비로그인 열람이다 — 상단 칩과 행사방 열람은 로그인을 요구하지
 * 않고 쓰기(업로드 MSG-440 · 댓글 MSG-441 · 알림 MSG-442)부터 로그인이다. principal 은 로그인 상태에서만
 * 채워지므로 null 을 정상 입력으로 다룬다.
 */
@Tag(name = "행사 (Events)", description = "지도 홈 행사 칩·행사방 헤더·행사 위치 목록 조회 API.")
@RestController
@RequiredArgsConstructor
public class EventOccurrenceController {

	private final EventQueryService eventQueryService;

	@Operation(
		summary = "뷰포트 내 행사 회차 목록 조회",
		description = "지도 화면 bbox(남서~북동 좌표) 안에 노출 영역이 걸친 행사 회차를 반환한다. 담기는 것은 "
			+ "진행 중이거나, 시작 2주 전부터의 노출 기간에 든 예정 회차뿐이다 — 종료된 행사(업로드 유예·"
			+ "아카이브)는 칩에 담기지 않고 상세·격자 역조회로만 접근한다. 아직 노출 기간 전인 예정 회차는 "
			+ "존재 자체를 숨긴다.\n\n"
			+ "정렬은 시 이름 → 시작일 → 회차 id 오름차순이라, 시 칩 아래에 그 시의 행사 칩을 나열하는 화면이 "
			+ "매 요청 같은 순서를 받는다. 보이는 범위에 행사가 없으면 실패가 아니라 빈 배열이다.\n\n"
			+ "bbox span 상한은 0.5도로 위도·경도 각 변에 따로 적용된다(정확히 0.5도는 허용). 초과 시 400 + "
			+ "developCode 13401, 좌표가 WGS84 범위를 벗어나거나 bbox 가 뒤집히거나 파라미터가 빠지면 13400 이다. "
			+ "D-day 는 startsAt 을 KST 로 읽어 클라이언트가 계산한다."
	)
	@GetMapping("/api/event-occurrences")
	public SuccessResponse<List<EventOccurrenceChipResponseDto>> getOccurrencesInViewport(
		@Parameter(description = "남서 모서리 위도", required = true, example = "35.10")
		@RequestParam(required = false) Double swLat,
		@Parameter(description = "남서 모서리 경도", required = true, example = "128.90")
		@RequestParam(required = false) Double swLng,
		@Parameter(description = "북동 모서리 위도", required = true, example = "35.20")
		@RequestParam(required = false) Double neLat,
		@Parameter(description = "북동 모서리 경도", required = true, example = "129.10")
		@RequestParam(required = false) Double neLng
	) {
		return SuccessResponse.of(eventQueryService.getOccurrencesInViewport(toBounds(swLat, swLng, neLat, neLng)));
	}

	@Operation(
		summary = "행사 회차 상세 조회",
		description = "행사방 헤더 재료 — 행사명, 기간, 업로드 마감(종료 30일 후), 서버 시각 기준 상태, 알림 구독 "
			+ "여부, 같은 시리즈의 지난 회차 목록이다. 상태는 저장값이 아니라 요청 시점 계산이며 경계 정각은 "
			+ "다음 상태에 속한다(종료 정각부터 UPLOAD_GRACE).\n\n"
			+ "지난 회차는 최신순이고 예정 회차는 담기지 않는다. 그 회차의 위치·영상은 회차 id 로 위치 목록을 "
			+ "다시 부르면 되므로 회차 간 데이터가 섞이지 않는다. 알림 구독 여부는 구독을 켰으면서 회차가 예정이거나 "
			+ "진행 중일 때만 true 다 — 비로그인 열람과 종료된 회차는 false 다.\n\n"
			+ "존재하지 않는 회차와 아직 노출 기간 전인 예정 회차는 똑같이 404 + "
			+ "developCode 13404 다 — 노출 전 행사의 존재를 id 대입으로 알아낼 수 없다."
	)
	@GetMapping("/api/event-occurrences/{occurrenceId}")
	public SuccessResponse<EventOccurrenceDetailResponseDto> getOccurrenceDetail(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "행사 회차 id", example = "12") @PathVariable long occurrenceId
	) {
		return SuccessResponse.of(eventQueryService.getOccurrenceDetail(occurrenceId, userId(principal)));
	}

	@Operation(
		summary = "행사 회차의 위치 목록 조회",
		description = "회차에 속한 행사 위치(팝업·체험존·퍼레이드 등)와 각 위치의 격자 영역, 대표 격자, 표시명 "
			+ "재료, 영상 수를 반환한다. 영상 수는 집계 테이블 없이 조회 시점에 세며, 위치별 영상 피드에 실제로 "
			+ "보이는 영상만 센다(삭제·비공개·처리 미완료 제외).\n\n"
			+ "gridIds 는 화면에서 영역을 채색하는 재료이고 영상은 그중 representativeGridId 하나에만 붙는다. "
			+ "표시명은 대표 격자 기준으로 `zoneName + \" \" + zoneCell`, 구역 밖이면 regionName 을 쓴다. "
			+ "정렬은 표시 순서 → 위치 id 오름차순이다. 위치가 없으면 빈 배열이고, 존재하지 않는 회차와 노출 "
			+ "기간 전인 예정 회차는 404 + developCode 13404 다."
	)
	@GetMapping("/api/event-occurrences/{occurrenceId}/locations")
	public SuccessResponse<List<EventLocationResponseDto>> getLocations(
		@Parameter(description = "행사 회차 id", example = "12") @PathVariable long occurrenceId
	) {
		return SuccessResponse.of(eventQueryService.getLocations(occurrenceId));
	}

	/**
	 * bbox 는 required = false 로 받아 여기서 검증한다 — Spring 의 필수 파라미터 예외는 도메인 developCode 를
	 * 못 싣기 때문이다(MissionController.toBounds 동일 패턴, @Parameter(required = true) 가 문서 계약을 지킨다).
	 */
	private ViewportBounds toBounds(Double swLat, Double swLng, Double neLat, Double neLng) {
		if (swLat == null || swLng == null || neLat == null || neLng == null) {
			throw new ApiException(EventErrorCode.INVALID_VIEWPORT);
		}
		return new ViewportBounds(swLat, swLng, neLat, neLng);
	}

	/** 비로그인 요청은 principal 이 없다 — 알림 구독 여부(MSG-442)의 조회 키라 null 을 그대로 넘긴다(항상 false). */
	private Long userId(AuthPrincipal principal) {
		return principal == null ? null : principal.userId();
	}
}
