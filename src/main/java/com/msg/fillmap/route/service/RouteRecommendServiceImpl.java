package com.msg.fillmap.route.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto.ViewportDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto;
import com.msg.fillmap.route.exception.RouteErrorCode;

/**
 * AI 경로 추천 구현 (MSG-457). 상시 빈이다 — RouteIntentClient 만 플래그 조건부라, 직접 주입하면 기본
 * (enabled=false) 기동이 깨지고 서비스·컨트롤러까지 조건부로 만들면 14503 대신 404 가 나간다
 * (HighlightPreviewServiceImpl 의 ObjectProvider 선례와 같은 근거). 뷰포트 의미 검증은 parse 호출 전에
 * 여기서 한다 — 통과 못 한 요청이 AI 까지 가서 422 로 돌아오면 의미가 다른 14502 로 샌다 (§API).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteRecommendServiceImpl implements RouteRecommendService {

	/** 뷰포트 한 변의 위경도 span 상한(도) — 미션·행사 뷰포트 조회와 같은 값. 정확히 0.5도는 허용. */
	private static final double MAX_VIEWPORT_SPAN_DEG = 0.5;

	// WGS84 좌표계 정의역 — 범위 비교가 NaN(어떤 비교도 false)·±무한대까지 걸러낸다.
	private static final double MIN_LATITUDE_DEG = -90.0;
	private static final double MAX_LATITUDE_DEG = 90.0;
	private static final double MIN_LONGITUDE_DEG = -180.0;
	private static final double MAX_LONGITUDE_DEG = 180.0;

	// ObjectProvider: RouteIntentClient 는 route.ai.enabled 일 때만 뜨는 빈이라 직접 주입하면 기동이 깨진다.
	private final ObjectProvider<RouteIntentClient> intentClientProvider;

	@Override
	public RouteRecommendResponseDto recommend(long userId, RouteRecommendRequestDto request) {
		validateViewport(request.viewport());
		RouteIntentClient intentClient = intentClientProvider.getIfAvailable();
		if (intentClient == null) {
			// 플래그 꺼짐(기본)의 명시적 비활성 응답 — FE 는 404(기능 없음)와 구분해 안내한다 (비기능 운영).
			throw new ApiException(RouteErrorCode.ROUTE_DISABLED);
		}
		// 미구현 스텁 (MSG-457 모듈 1 범위) — 요청 제한(FR-ROUTE-12) → parse 해석 캐시 → 후보 수집 →
		// 순서 배열 → explain → 응답 조립은 다음 모듈에서 구현한다. 도달 경로는 플래그 on + 유효 입력뿐이다.
		throw new UnsupportedOperationException("MSG-457 추천 본 로직 미구현 — 후보 수집·순서 배열 모듈 대기");
	}

	/**
	 * 뷰포트 의미 검증 (parse 호출 전, §API) — 순서는 좌표 유효성 → 뒤집힘·넓이 0 → span 상한이다.
	 * min == max(넓이 0)도 14400 인 점이 미션·행사 검증(등호 통과)과 다르다 — AI 의 Viewport 검증(엄격,
	 * 등호도 422)과 같은 규칙을 BE 가 먼저 적용하는 것이 이 검증의 존재 이유라서다.
	 * ponytail: EventQueryServiceImpl.validateBounds 계열의 네 번째 복제 — 공통 validator 승격은
	 * 2026-09-07 멘토 라이브 코드 리뷰 전 구조 변경 금지 합의로 유예 (MSG-439 §API 1 과 같은 조건).
	 */
	private void validateViewport(ViewportDto viewport) {
		if (!isValidLat(viewport.minLat()) || !isValidLat(viewport.maxLat())
			|| !isValidLng(viewport.minLng()) || !isValidLng(viewport.maxLng())) {
			throw new ApiException(RouteErrorCode.INVALID_VIEWPORT);
		}
		if (viewport.minLat() >= viewport.maxLat() || viewport.minLng() >= viewport.maxLng()) {
			throw new ApiException(RouteErrorCode.INVALID_VIEWPORT);
		}
		if (viewport.maxLat() - viewport.minLat() > MAX_VIEWPORT_SPAN_DEG
			|| viewport.maxLng() - viewport.minLng() > MAX_VIEWPORT_SPAN_DEG) {
			throw new ApiException(RouteErrorCode.VIEWPORT_TOO_LARGE);
		}
	}

	private boolean isValidLat(double lat) {
		return lat >= MIN_LATITUDE_DEG && lat <= MAX_LATITUDE_DEG;
	}

	private boolean isValidLng(double lng) {
		return lng >= MIN_LONGITUDE_DEG && lng <= MAX_LONGITUDE_DEG;
	}
}
