package com.msg.fillmap.search.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.KoreaCoordinates;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.search.dto.PlaceSearchResponseDto;
import com.msg.fillmap.search.exception.SearchErrorCode;
import com.msg.fillmap.search.service.PlaceSearchService;

/**
 * 장소 검색 API (MSG-251 · 카카오 로컬 프록시). 3-layer 얇게 — 파싱 + 서비스 호출 + SuccessResponse 변환만.
 * 검색 결과는 개인 데이터가 아니지만 검색어 집계 dedupe(검색자·검색어당 일 1회, MSG-258 §D1)에 검색자
 * 식별값이 필요하다 — 결과 개인화는 없다. 검색자 식별은 principal 우선이고, 비로그인이면 X-Viewer-Session
 * 헤더(방문자 세션 값)를 쓴다. 그 값이 없거나 비었거나 64자를 넘거나 콜론을 포함하면 집계만 건너뛰고 검색은
 * 정상 200 이다 — 화면이 헤더를 붙이기 전에도 검색이 막히지 않아야 한다(MSG-469 D3). 비로그인 열람 허용
 * (SecurityConfig permitAll, MSG-469). q 누락 400 은 global 핸들러 소관이라 신규 코드가 없다(§D3).
 */
@Tag(name = "장소 검색 (Search)", description = "장소명 자유 텍스트 검색 — 카카오 로컬 키워드 검색 실시간 프록시 + 격자 ID 합성.")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class PlaceSearchController {

	/** MSG-443 EventViewerController 와 같은 헤더다 — 브라우저가 익명으로 드는 방문자 세션 값은 하나면 된다. */
	private static final String VIEWER_SESSION_HEADER = "X-Viewer-Session";
	/** 익명 세션 값 길이 상한. MSG-443 EventViewerController 와 같은 값이고 이유도 같다(키 크기 증폭 방지). */
	private static final int MAX_SESSION_ID_LENGTH = 64;

	private final PlaceSearchService placeSearchService;

	@Operation(
		summary = "장소 검색 (장소명 → 좌표·격자)",
		description = "카카오 로컬 키워드 검색 결과(정확도순 ≤15건)에 각 좌표의 격자 ID 를 얹어 반환한다. "
			+ "선택 즉시 lat/lng 지도 이동 + gridId 격자 하이라이트. q 누락 400 / trim 후 빈 q·무매치 200 [] / "
			+ "카카오 장애·타임아웃 502(developCode 5502). 비로그인도 호출할 수 있고 결과는 로그인 때와 같다 — "
			+ "비로그인 호출은 X-Viewer-Session 헤더(공백 아님·최대 64자·콜론 불가)를 실으면 인기 검색어 집계에 "
			+ "잡히고, 안 실어도 검색은 정상 200 이다.\n\n"
			+ "lat·lng 에 지금 보고 있는 지도의 중심 좌표를 실으면 그 중심 반경 20km 안의 장소를 먼저 찾는다. "
			+ "근처에 결과가 하나도 없으면 위치 없이 다시 찾아 전국 결과를 주므로 좌표를 붙였다는 이유로 결과가 "
			+ "사라지지는 않는다. 두 값은 반드시 한 쌍으로 보내야 하고, 한쪽만 오거나 숫자가 아니거나 대한민국 "
			+ "범위(위도 33~39·경도 124~132) 밖이면 400 + developCode 5400 이다. 좌표를 아예 안 보내면 종전과 "
			+ "똑같이 동작한다."
	)
	@GetMapping("/places")
	public SuccessResponse<List<PlaceSearchResponseDto>> searchPlaces(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "검색어 (자유 텍스트 장소명)", example = "부산대")
		@RequestParam String q,
		@Parameter(description = "지도 중심 위도 (33.0~39.0). lng 과 한 쌍으로만 유효하다", example = "35.1578",
			schema = @Schema(type = "number", format = "double"))
		@RequestParam(required = false) String lat,
		@Parameter(description = "지도 중심 경도 (124.0~132.0). lat 과 한 쌍으로만 유효하다", example = "129.0594",
			schema = @Schema(type = "number", format = "double"))
		@RequestParam(required = false) String lng,
		@RequestHeader(value = VIEWER_SESSION_HEADER, required = false) String viewerSession
	) {
		// 한쪽만 온 좌표는 클라이언트 실수다 — 조용히 무시하면 "왜 위치 랭킹이 안 먹지"를 추적할 단서가 없다 (§D2)
		if ((lat == null) != (lng == null)) {
			throw new ApiException(SearchErrorCode.INVALID_COORDINATE);
		}
		Double centerLat = null;
		Double centerLng = null;
		if (lat != null) {
			centerLat = parseCoordinate(lat);
			centerLng = parseCoordinate(lng);
			// 국내 장소 검색의 중심점이라 국외·NaN·무한대는 정상 입력이 아니다 — 업로드·역지오코딩과 같은 경계(§D3)
			if (KoreaCoordinates.isOutOfService(centerLat, centerLng)) {
				throw new ApiException(SearchErrorCode.INVALID_COORDINATE);
			}
		}
		return SuccessResponse.of(placeSearchService.searchPlaces(
			resolveSearcherKey(principal, viewerSession), q, centerLat, centerLng));
	}

	/**
	 * 좌표 문자열 → double (§D2). Double 로 직접 바인딩하면 숫자가 아닌 값이 스프링 바인딩 단계에서
	 * MethodArgumentTypeMismatchException 이 되고 전역 핸들러가 developCode 400 으로 바꿔, 5400 이 나오지 않는다.
	 */
	private static double parseCoordinate(String value) {
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			throw new ApiException(SearchErrorCode.INVALID_COORDINATE);
		}
	}

	/**
	 * 검색자 식별값 산출 (MSG-469 D3). principal 우선 — 로그인 요청은 헤더가 함께 와도 사용자 기준으로 센다.
	 * null 은 "집계 대상 아님"이고 검색 자체는 그대로 수행된다(400 을 내지 않는다).
	 */
	private String resolveSearcherKey(AuthPrincipal principal, String viewerSession) {
		if (principal != null) {
			return String.valueOf(principal.userId());
		}
		// 콜론은 dedupe member 의 구분자라 값에 들어오면 세션과 검색어의 경계가 모호해진다 (D4)
		if (viewerSession == null || viewerSession.isBlank()
			|| viewerSession.length() > MAX_SESSION_ID_LENGTH || viewerSession.indexOf(':') >= 0) {
			return null;
		}
		return "s:" + viewerSession;
	}
}
