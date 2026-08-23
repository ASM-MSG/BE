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
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.dto.GridMissionResponseDto;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto;
import com.msg.fillmap.mission.dto.MissionProgressResponseDto;
import com.msg.fillmap.mission.dto.MissionRegionAggregateResponseDto;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 미션 조회 API (MSG-222 → MSG-398 뷰포트 자르기·진행도). 목록·집계·상세는 비로그인 열람이다
 * (MSG-454 — 지도 홈 상단 칩은 로그인 없이 보인다). 목록·집계는 응답이 호출자와 무관해야 전역 캐시가
 * 성립하고(FR-MISSION-02), principal 을 아예 받지 않는 것이 그 계약의 코드 수준 방어다(D10). 상세는
 * principal 이 있으면 그 사용자의 진행도를 담고 없으면 개인화 필드를 null 로 내린다. 진행도 조회는
 * 사용자별 값이라 로그인 필수이고 캐시하지 않는다.
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
		return SuccessResponse.of(missionQueryService.getMissionsInViewport(bounds, toType(type, LISTABLE_TYPES)));
	}

	@Operation(
		summary = "넓은 축척용 미션 행정 단위 집계 조회 (줌아웃)",
		description = "지도를 축소해 개별 핀을 그릴 수 없는 축척에서, bbox 안의 축제·팝업 미션을 행정 단위"
			+ "(동·구·시)로 묶어 지역 이름과 개수로 반환한다. 단위 전환 시점은 서버가 정하지 않으며 "
			+ "클라이언트가 화면 축척에 맞춰 unit 만 바꿔 부른다.\n\n"
			+ "항목마다 마커 식별 키(regionCode), 표시 이름, 대표 좌표, 미션 수, 그 묶음의 미션 id 목록이 온다. "
			+ "대표 좌표는 묶음에 속한 미션 귀속점의 평균이라 마커가 실제 데이터 위에 선다. missionIds 는 "
			+ "묶음 마커를 눌러 줌인한 뒤 개별 조회(GET /api/missions/active) 결과와 교집합을 내 목록을 좁히는 "
			+ "재료다 — 카드 재료는 개별 조회 응답에 있다.\n\n"
			+ "미션이 속한 격자 사각형이 아니라 그 사각형 중앙의 귀속점이 bbox 안인지로 센다. 사각형이 화면에 "
			+ "걸쳤지만 중심이 밖인 미션은 빠지며, 이 때문에 개별 조회와 집계를 갈아타는 순간 마커 수가 미세하게 "
			+ "달라질 수 있다. 행정동이 판정되지 않은 미션은 제외가 아니라 regionCode·name 이 null 인 항목 "
			+ "하나로 묶여 마지막에 온다. 범위 안에 미션이 없으면 빈 배열이다.\n\n"
			+ "bbox span 상한은 단위별로 다르다(DONG 1도, SIGUNGU 4도, SIDO 10도 — 위도·경도 각 변에 따로 적용, "
			+ "정확히 상한값은 허용). 초과 시 400 + developCode 12401, 좌표가 WGS84 범위를 벗어나거나 bbox 가 "
			+ "뒤집히면 12400, type 이 없거나 EVENT·POPUP 이 아니면 12402, unit 이 없거나 미지원 값이면 12405 다. "
			+ "응답에 사용자별 값은 없다."
	)
	@GetMapping("/api/missions/aggregation")
	public SuccessResponse<List<MissionRegionAggregateResponseDto>> getMissionAggregates(
		@Parameter(description = "미션 종류 — EVENT(지역축제), POPUP(팝업스토어). 대소문자 무관",
			required = true, example = "POPUP")
		@RequestParam(required = false) String type,
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
		// 검증 순서가 곧 응답 코드다 (§API 명세) — bbox 누락 12400 → type 12402 → unit 12405 →
		// 좌표 정의역·뒤집힘 12400 → span 상한 12401(서비스 validateBounds).
		ViewportBounds bounds = toBounds(swLat, swLng, neLat, neLng);
		// 허용 종류는 서비스 계약 상수 하나가 정본이다 — 컨트롤러가 따로 들면 여기만 열린 유형이
		// 12402 대신 늘 빈 배열로 나간다.
		MissionType missionType = toType(type, MissionQueryService.AGGREGATABLE_TYPES);
		return SuccessResponse.of(missionQueryService.getMissionAggregates(bounds, missionType, toUnit(unit)));
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
			+ "수와 항상 같다). 존재하지 않는 미션 ID 는 404 + developCode 12404(MISSION_NOT_FOUND)다.\n\n"
			+ "비로그인으로도 조회된다(MSG-454). 이때 사용자별 값은 빠진다 — progress 는 키는 있고 값이 null 이며 "
			+ "spotStats[].visited 는 전부 false 다. 미션 정보·전체 영상 수·스팟별 영상 수는 로그인과 같다."
	)
	@GetMapping("/api/missions/{missionId}")
	public SuccessResponse<MissionDetailResponseDto> getMissionDetail(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "미션 ID", example = "412") @PathVariable long missionId
	) {
		return SuccessResponse.of(missionQueryService.getMissionDetail(missionId, userId(principal)));
	}

	@Operation(
		summary = "격자가 대표 격자인 미션 조회",
		description = "지도에서 누른 격자가 어느 축제·팝업 미션의 자리인지 되짚는다. 미션 경유로 올린 영상은 "
			+ "그 미션의 대표 격자 한 칸에만 저장되므로, 영상이 모인 칸을 눌러 무슨 미션이었는지 확인하는 "
			+ "경로다.\n\n"
			+ "기간 필터가 없다 — 끝난 축제도 담긴다. 진행 중인지 시작 전인지 끝났는지는 startAt·endAt 을 "
			+ "서버 시각과 견주어 화면이 판정한다. 배열 첫 항목이 화면 진입 기본값이 되도록 진행 중 → 시작 전"
			+ "(임박한 순) → 종료(최근 종료 순)로 정렬한다.\n\n"
			+ "판정 범위(축제 9×9)에만 걸친 격자는 나오지 않는다 — 나오는 것은 영상이 모인 자리로 지목된 "
			+ "미션뿐이다. 어떤 미션의 대표 격자도 아닌 격자와 격자 형식이 아닌 문자열은 오류가 아니라 빈 "
			+ "배열이다. videoCount 는 미션 상세의 videoCount 와 같은 술어라 두 화면의 숫자가 어긋나지 않는다. "
			+ "비로그인으로도 조회할 수 있다."
	)
	@GetMapping("/api/grids/{gridId}/missions")
	public SuccessResponse<List<GridMissionResponseDto>> getMissionsByGrid(
		@Parameter(description = "격자 id — \"{gridY}_{gridX}\" 포맷", example = "19443_9582")
		@PathVariable String gridId
	) {
		return SuccessResponse.of(missionQueryService.getMissionsByGrid(gridId));
	}

	/** 비로그인 상세 요청은 principal 이 없다 — 개인화 필드(진행도·방문)의 조회 키라 null 을 그대로 넘긴다. */
	private Long userId(AuthPrincipal principal) {
		return principal == null ? null : principal.userId();
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

	/** 허용 집합은 경로마다 다르다 — 목록은 코스를 포함하고(LISTABLE_TYPES) 집계는 축제·팝업뿐이다. */
	private MissionType toType(String type, Set<MissionType> allowed) {
		if (type == null) {
			throw new ApiException(MissionErrorCode.INVALID_MISSION_TYPE);
		}
		MissionType parsed;
		try {
			parsed = MissionType.valueOf(type.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(MissionErrorCode.INVALID_MISSION_TYPE, e);
		}
		if (!allowed.contains(parsed)) {
			throw new ApiException(MissionErrorCode.INVALID_MISSION_TYPE);
		}
		return parsed;
	}

	/** unit 도 같은 이유로 required = false 로 받아 여기서 검증한다 (GridController.toUnit 동일 패턴). */
	private RegionUnit toUnit(String unit) {
		if (unit == null) {
			throw new ApiException(MissionErrorCode.INVALID_AGGREGATION_UNIT);
		}
		try {
			return RegionUnit.valueOf(unit.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(MissionErrorCode.INVALID_AGGREGATION_UNIT, e);
		}
	}
}
