package com.msg.fillmap.mission.controller;

import java.util.List;
import java.util.Locale;
import java.util.Set;

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
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto;
import com.msg.fillmap.mission.dto.MissionProgressResponseDto;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 미션 조회 API (MSG-222 → MSG-398 뷰포트 자르기·진행도). 목록은 전역 데이터라 principal 을 읽지 않지만
 * 인증은 필수다(SecurityConfig anyRequest().authenticated()) — 응답이 호출자와 무관해야 전역 캐시가
 * 성립하고(FR-MISSION-02), principal 을 안 받는 것이 그 계약의 코드 수준 방어다(D10). 진행도는 사용자별
 * 값이라 principal 로 사용자를 정하고 캐시하지 않는다.
 */
@Tag(name = "미션 (Missions)", description = "지도 오버레이용 활성 미션 목록·내 진행도·미션 상세 조회 API.")
@RestController
@RequiredArgsConstructor
public class MissionController {

	/** 화면에 칩이 있는 세 종류만 연다 (MSG-398 §API 명세) — AREA·THEME·CONTINUOUS 는 대응 칩이 없고 적재 0건. */
	private static final Set<MissionType> LISTABLE_TYPES =
		Set.of(MissionType.EVENT, MissionType.POPUP, MissionType.COURSE);

	private final MissionQueryService missionQueryService;

	@Operation(
		summary = "뷰포트 내 활성 미션 목록 조회",
		description = "지도 화면 bbox(남서~북동 좌표) 안의, 고른 종류(type)의 활성 미션을 유형별 렌더 shape"
			+ "(코스=PATH·축제/팝업=BOX)로 반환한다. bbox span 상한은 0.5도로 위도·경도 각 변에 따로 적용된다"
			+ "(정확히 0.5도는 허용). 초과 시 잘라서 응답하지 않고 400 + developCode 12401(VIEWPORT_TOO_LARGE)로 "
			+ "거절한다. 클라이언트는 격자 개별 조회(GET /api/grids)를 멈추는 것과 같은 0.5도 지점에서 이 조회도 "
			+ "멈추고 확대 안내를 그린다.\n\n"
			+ "보이는 범위에 그 종류 미션이 없으면 실패가 아니라 빈 배열이다(뷰포트가 너무 넓은 12401 과 다른 "
			+ "상태). 한국 밖이지만 WGS84 정의역 안인 bbox 도 오류가 아니라 빈 배열이다. 응답에 사용자별 값은 "
			+ "없다 — 진행도는 GET /api/missions/progress 로 따로 받는다."
	)
	@GetMapping("/api/missions/active")
	public SuccessResponse<List<MissionResponseDto>> getActiveMissionsInViewport(
		@Parameter(description = "미션 종류 — EVENT(지역축제), POPUP(팝업스토어), COURSE(경로추천). 대소문자 무관",
			required = true, example = "POPUP")
		@RequestParam(required = false) String type,
		@Parameter(description = "남서 모서리 위도", required = true, example = "37.50")
		@RequestParam(required = false) Double swLat,
		@Parameter(description = "남서 모서리 경도", required = true, example = "127.00")
		@RequestParam(required = false) Double swLng,
		@Parameter(description = "북동 모서리 위도", required = true, example = "37.55")
		@RequestParam(required = false) Double neLat,
		@Parameter(description = "북동 모서리 경도", required = true, example = "127.05")
		@RequestParam(required = false) Double neLng
	) {
		ViewportBounds bounds = toBounds(swLat, swLng, neLat, neLng);
		return SuccessResponse.of(missionQueryService.getMissionsInViewport(bounds, toType(type)));
	}

	@Operation(
		summary = "미션별 내 진행도 조회",
		description = "미션 id 여러 개의 내 진행도(채운 칸/목표 칸)와 스탬프 보유 여부를 한 번에 반환한다. "
			+ "채운 칸은 스탬프 판정과 같은 술어로 센다 — 미션 기간 안에 촬영한 내 영상(삭제 제외)이 있는 "
			+ "격자 수다. 영상을 전부 지우면 진행도는 0으로 돌아가지만 스탬프는 비회수라 completed 는 남는다 "
			+ "— \"0/1 인데 완료\"가 정상 응답이다.\n\n"
			+ "missionIds 가 없거나 비면 빈 배열이고(오류 아님), 존재하지 않는 id 는 응답에서 빠진다. "
			+ "기간이 끝난 미션도 조회된다. 배열 순서는 missionId 오름차순으로 고정된다(요청 순서 미보존). "
			+ "300개 초과는 400 + developCode 12403 으로 거절한다."
	)
	@GetMapping("/api/missions/progress")
	public SuccessResponse<List<MissionProgressResponseDto>> getMyProgress(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "미션 id 목록 — 콤마 구분 또는 반복 파라미터. 없거나 비면 빈 배열 응답, 300개 초과는 거절",
			example = "412,413")
		@RequestParam(required = false) List<Long> missionIds
	) {
		return SuccessResponse.of(missionQueryService.getMyProgress(principal.userId(), missionIds));
	}

	@Operation(
		summary = "미션 상세 조회",
		description = "미션 ID 하나의 상세 — 미션 정보와 렌더 shape(목록과 같은 필드), 내 진행도와 스탬프 보유 "
			+ "여부, 이 미션에 올라온 전체 영상 개수, 코스라면 포토스팟별 방문 여부·영상 개수를 한 번에 "
			+ "반환한다. spotStats 는 shape.spots 와 같은 순서로 오고, 코스가 아니면 null 대신 빈 배열이다.\n\n"
			+ "기간 판정은 하지 않는다 — 기간이 끝난 미션도 행이 남아 있으면 조회되고, 영상 개수는 그 미션이 "
			+ "활성일 때 촬영된 것만 센다(미션 영상 목록 GET /api/missions/{missionId}/videos 의 실제 후보 "
			+ "수와 항상 같다). 존재하지 않는 미션 ID 는 404 + developCode 12404(MISSION_NOT_FOUND)다."
	)
	@GetMapping("/api/missions/{missionId}")
	public SuccessResponse<MissionDetailResponseDto> getMissionDetail(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "미션 ID", example = "412") @PathVariable long missionId
	) {
		return SuccessResponse.of(missionQueryService.getMissionDetail(missionId, principal.userId()));
	}

	/**
	 * bbox 는 required = false 로 받아 여기서 검증한다 — Spring 의 필수 파라미터 예외는 도메인 developCode 를
	 * 못 싣기 때문이다(@Parameter(required = true) 가 문서 쪽 계약을 지킨다, GridController.toBounds 동일 패턴).
	 */
	private ViewportBounds toBounds(Double swLat, Double swLng, Double neLat, Double neLng) {
		if (swLat == null || swLng == null || neLat == null || neLng == null) {
			throw new ApiException(MissionErrorCode.INVALID_VIEWPORT);
		}
		return new ViewportBounds(swLat, swLng, neLat, neLng);
	}

	private MissionType toType(String type) {
		if (type == null) {
			throw new ApiException(MissionErrorCode.INVALID_MISSION_TYPE);
		}
		MissionType parsed;
		try {
			parsed = MissionType.valueOf(type.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(MissionErrorCode.INVALID_MISSION_TYPE, e);
		}
		if (!LISTABLE_TYPES.contains(parsed)) {
			throw new ApiException(MissionErrorCode.INVALID_MISSION_TYPE);
		}
		return parsed;
	}
}
