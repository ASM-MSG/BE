package com.msg.fillmap.route.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import com.msg.fillmap.route.dto.RouteRecommendRequestDto.OriginDto;

/**
 * 방문 순서 배열 (MSG-457 §도메인 로직 2). 결정적 규칙만 쓴다(FR-ROUTE-10) — 난수·시각·순서 비보장
 * 자료구조 의존이 없는 순수 함수라 같은 입력이면 같은 출력이다. 지점 수 상한(8)은 후보 선별
 * (RouteCandidateCollector)이 이미 보장하고, 여기서는 순서 확정 뒤 도보 예산 절단(MSG-515)으로
 * 입력을 줄일 수만 있다 — 늘리지는 않는다.
 */
public final class RouteOrderPlanner {

	private static final double EARTH_RADIUS_METERS = 6371000.0;

	/** 총 이동 거리 상한 — 보정 누적이 이 값을 처음 넘기는 지점부터 버린다. 정확히 상한이면 담는다(닫힌 상한, MSG-515). */
	private static final double MAX_WALK_DISTANCE_METERS = 10_000;

	/** 도보 우회 계수 — 실보행 거리는 직선거리의 통상 1.2~1.4배(도시 보행망 실측), 그 중앙값이다 (MSG-515 결정 1). */
	private static final double WALK_DETOUR_FACTOR = 1.3;

	private RouteOrderPlanner() {
	}

	/**
	 * 1) preferred_order 힌트와 이름이 부분 일치하는 후보를 힌트 순서대로 앞에 배치한다 — 일치하지 않는
	 * 힌트는 무시하고 후보를 만들지 않는다(FR-ROUTE-03). 힌트는 사용자가 순서 자체를 지목한 것이라
	 * 관심사보다 강한 신호다(MSG-514 §도메인 로직 3). 2) 나머지는 (관심사 일치 여부, 거리, gridId) 비교
	 * 키의 최근접 이웃으로 잇는다 — 일치 지점이 남아 있는 동안 그중 최근접이 먼저 뽑히므로 일치 전부가
	 * 미일치보다 앞에 온다. 시작점은 origin(FR-ROUTE-11), 없으면 힌트 배치의 마지막 지점, 그것도 없으면
	 * 뷰포트 중심이다. 3) 거리 동률은 gridId 사전순으로 끊는다. 4) 확정된 최종 시퀀스를 앞에서부터 걸으며
	 * 보정 거리(WALK_DETOUR_FACTOR × 하버사인)를 누적하고, 상한을 처음 넘기게 만드는 지점부터 전부 버린다 —
	 * origin 이 있으면 origin→첫 지점이 첫 구간이고, 없으면 첫 지점은 누적 0에서 담긴다 (MSG-515 FR-ROUTE-13).
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
					.comparing((RouteCandidate candidate) -> candidate.matchedInterest() == null)
					.thenComparingDouble(candidate ->
						distanceMeters(fromLat, fromLng, candidate.lat(), candidate.lng()))
					.thenComparing(RouteCandidate::gridId))
				.orElseThrow();
			ordered.add(next);
			remaining.remove(next);
			lat = next.lat();
			lng = next.lng();
		}
		return truncateToWalkBudget(ordered, origin);
	}

	/**
	 * 도보 예산 절단 (MSG-515 결정 2) — 판정은 선택 루프가 아니라 순서가 확정된 최종 방문 시퀀스 기준이다.
	 * origin 과 힌트가 같이 있으면 선택에 쓴 거리(origin 기준)와 실제 걷는 구간(힌트 마지막 지점 다음)이
	 * 달라서, FR-1 의 "총 이동 거리"를 정확히 재는 위치는 여기뿐이다. 힌트 지점도 예산 면제가 아니다 —
	 * 힌트는 순서 신호이지 예산 신호가 아니다.
	 */
	private static List<RouteCandidate> truncateToWalkBudget(List<RouteCandidate> ordered, OriginDto origin) {
		List<RouteCandidate> kept = new ArrayList<>();
		double accumulated = 0;
		boolean hasPrevious = origin != null;
		double lat = hasPrevious ? origin.lat() : 0;
		double lng = hasPrevious ? origin.lng() : 0;
		for (RouteCandidate candidate : ordered) {
			if (hasPrevious) {
				accumulated += WALK_DETOUR_FACTOR * distanceMeters(lat, lng, candidate.lat(), candidate.lng());
				if (accumulated > MAX_WALK_DISTANCE_METERS) {
					break;	// 접두 절단 — 넘긴 지점만 건너뛰고 계속 담으면 일치 우선 순서의 의미가 뒤집힌다
				}
			}
			kept.add(candidate);
			lat = candidate.lat();
			lng = candidate.lng();
			hasPrevious = true;
		}
		return kept;
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
