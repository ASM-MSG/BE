package com.msg.fillmap.search.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.search.dto.PlaceSearchResponseDto;
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
			+ "잡히고, 안 실어도 검색은 정상 200 이다."
	)
	@GetMapping("/places")
	public SuccessResponse<List<PlaceSearchResponseDto>> searchPlaces(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "검색어 (자유 텍스트 장소명)", example = "부산대")
		@RequestParam String q,
		@RequestHeader(value = VIEWER_SESSION_HEADER, required = false) String viewerSession
	) {
		return SuccessResponse.of(placeSearchService.searchPlaces(resolveSearcherKey(principal, viewerSession), q));
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
