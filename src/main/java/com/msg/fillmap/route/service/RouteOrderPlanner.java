package com.msg.fillmap.route.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import com.msg.fillmap.route.dto.RouteRecommendRequestDto.OriginDto;

/**
 * 방문 순서 배열 (MSG-457 §도메인 로직 2). 결정적 규칙만 쓴다(FR-ROUTE-10) — 난수·시각·순서 비보장
 * 자료구조 의존이 없는 순수 함수라 같은 입력이면 같은 출력이다. 지점 수 상한(8)은 후보 선별
 * (RouteCandidateCollector)이 이미 보장하므로 여기서는 입력 크기를 늘리지도 줄이지도 않는다.
 */
public final class RouteOrderPlanner {

	private static final double EARTH_RADIUS_METERS = 6371000.0;

	private RouteOrderPlanner() {
	}

	/**
	 * 1) preferred_order 힌트와 이름이 부분 일치하는 후보를 힌트 순서대로 앞에 배치한다 — 일치하지 않는
	 * 힌트는 무시하고 후보를 만들지 않는다(FR-ROUTE-03). 2) 나머지는 최근접 이웃으로 잇는다. 시작점은
	 * origin(FR-ROUTE-11), 없으면 힌트 배치의 마지막 지점, 그것도 없으면 뷰포트 중심이다.
	 * 3) 거리 동률은 gridId 사전순으로 끊는다.
	 */
	public static List<RouteCandidate> order(List<RouteCandidate> candidates, List<String> preferredOrder,
		OriginDto origin, double viewportCenterLat, double viewportCenterLng) {
		List<RouteCandidate> ordered = new ArrayList<>();
		List<RouteCandidate> remaining = new ArrayList<>(candidates);
		for (String hint : preferredOrder) {
			for (Iterator<RouteCandidate> iterator = remaining.iterator(); iterator.hasNext(); ) {
				RouteCandidate candidate = iterator.next();
				if (matchesHint(candidate.name(), hint)) {
					ordered.add(candidate);
					iterator.remove();
				}
			}
		}

		double lat;
		double lng;
		if (origin != null) {
			lat = origin.lat();
			lng = origin.lng();
		} else if (!ordered.isEmpty()) {
			RouteCandidate last = ordered.getLast();
			lat = last.lat();
			lng = last.lng();
		} else {
			lat = viewportCenterLat;
			lng = viewportCenterLng;
		}

		while (!remaining.isEmpty()) {
			double fromLat = lat;
			double fromLng = lng;
			RouteCandidate next = remaining.stream()
				.min(Comparator
					.comparingDouble((RouteCandidate candidate) ->
						distanceMeters(fromLat, fromLng, candidate.lat(), candidate.lng()))
					.thenComparing(RouteCandidate::gridId))
				.orElseThrow();
			ordered.add(next);
			remaining.remove(next);
			lat = next.lat();
			lng = next.lng();
		}
		return ordered;
	}

	/** 부분 일치 — 어느 쪽이 어느 쪽을 품어도 인정한다 (모델 힌트가 정식 명칭보다 짧거나 길 수 있다). */
	private static boolean matchesHint(String name, String hint) {
		return hint != null && !hint.isBlank() && (name.contains(hint) || hint.contains(name));
	}

	/** 대표 좌표 간 하버사인 — 도보 경로 계산은 비목표라 직선 거리면 충분하다(§도메인 로직 2). */
	public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLng = Math.toRadians(lng2 - lng1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
			+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
			* Math.sin(dLng / 2) * Math.sin(dLng / 2);
		return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
	}
}
