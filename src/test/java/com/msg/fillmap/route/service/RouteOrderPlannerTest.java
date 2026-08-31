package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto.OriginDto;
import com.msg.fillmap.route.service.RouteCandidate.Kind;

/**
 * 순서 배열 규칙 검증 (MSG-457 §도메인 로직 2). 순수 함수라 스프링 없이 규칙 자체를 고정한다 —
 * 힌트 배치·최근접 이웃·시작점 폴백·동률 tie-break 가 전부 결정적이어야 한다(FR-ROUTE-10).
 */
@DisplayName("RouteOrderPlanner — 방문 순서 배열 (결정적 규칙)")
class RouteOrderPlannerTest {

	// 부산 뷰포트 중심 근방 — 좌표만 의미 있고 격자 실데이터와 무관하다.
	private static final double 중심_LAT = 35.15;
	private static final double 중심_LNG = 129.075;

	private RouteCandidate 후보(String name, double lat, double lng) {
		return new RouteCandidate(name, Kind.PLACE, lat, lng, GridEncoder.encode(lat, lng),
			null, null, null, null, null, List.of());
	}

	private RouteCandidate 일치후보(String name, double lat, double lng) {
		return new RouteCandidate(name, Kind.PLACE, lat, lng, GridEncoder.encode(lat, lng),
			null, null, null, null, "맛집", List.of());
	}

	// 검증: FR-ROUTE-10
	@Test
	@DisplayName("같은 입력이면 같은 순서다 — 등거리 동률은 gridId 사전순으로 끊는다")
	void 같은_입력이면_같은_순서다() {
		// 동쪽·서쪽 후보는 시작점(중심)에서 정확히 등거리다 — 같은 위도, 경도차 대칭이라 하버사인 값이 같다.
		RouteCandidate 동쪽 = 후보("동쪽", 중심_LAT, 중심_LNG + 0.01);
		RouteCandidate 서쪽 = 후보("서쪽", 중심_LAT, 중심_LNG - 0.01);
		RouteCandidate 먼곳 = 후보("먼곳", 중심_LAT + 0.05, 중심_LNG);
		List<RouteCandidate> candidates = List.of(먼곳, 동쪽, 서쪽);

		List<RouteCandidate> first = RouteOrderPlanner.order(candidates, List.of(), null, 중심_LAT, 중심_LNG);
		List<RouteCandidate> second = RouteOrderPlanner.order(candidates, List.of(), null, 중심_LAT, 중심_LNG);

		assertThat(first).isEqualTo(second);
		// 동률의 승자는 gridId 사전순 — 이름이 아니라 격자 키가 기준이다.
		RouteCandidate 동률승자 = 동쪽.gridId().compareTo(서쪽.gridId()) < 0 ? 동쪽 : 서쪽;
		assertThat(first.getFirst()).isEqualTo(동률승자);
		assertThat(first.getLast()).isEqualTo(먼곳);
	}

	// 검증: FR-ROUTE-11
	@Test
	void 출발지를_지정하면_그_지점에서_시작한다() {
		RouteCandidate 북쪽 = 후보("북쪽", 중심_LAT + 0.04, 중심_LNG);
		RouteCandidate 남쪽 = 후보("남쪽", 중심_LAT - 0.04, 중심_LNG);
		List<RouteCandidate> candidates = List.of(북쪽, 남쪽);

		// origin 이 없으면 뷰포트 중심 기준 — 등거리 동률이 아니게 남쪽을 살짝 가깝게 두지 않고 origin 효과만 본다.
		List<RouteCandidate> 남쪽출발 = RouteOrderPlanner.order(
			candidates, List.of(), new OriginDto(중심_LAT - 0.05, 중심_LNG), 중심_LAT, 중심_LNG);
		List<RouteCandidate> 북쪽출발 = RouteOrderPlanner.order(
			candidates, List.of(), new OriginDto(중심_LAT + 0.05, 중심_LNG), 중심_LAT, 중심_LNG);

		assertThat(남쪽출발).containsExactly(남쪽, 북쪽);
		assertThat(북쪽출발).containsExactly(북쪽, 남쪽);
	}

	// 검증: FR-ROUTE-03
	@Test
	@DisplayName("힌트는 이름 부분 일치로 앞에 배치되고, 일치하지 않는 힌트는 후보를 만들지 않는다")
	void 일치하지_않는_힌트는_후보를_만들지_않는다() {
		RouteCandidate 축제 = 후보("해운대 빛축제", 중심_LAT + 0.03, 중심_LNG);
		RouteCandidate 해변 = 후보("광안리 해변", 중심_LAT - 0.01, 중심_LNG);
		List<RouteCandidate> candidates = List.of(해변, 축제);

		List<RouteCandidate> ordered = RouteOrderPlanner.order(
			candidates, List.of("빛축제", "유령카페"), null, 중심_LAT, 중심_LNG);

		// 힌트 일치(빛축제)가 거리와 무관하게 맨 앞, 불일치 힌트(유령카페)는 무시 — 지어낸 지점이 없다.
		assertThat(ordered).containsExactly(축제, 해변);
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("관심사 일치 지점이 미일치 지점보다 앞에 배열된다 — 비교 첫 키가 일치 여부다 (MSG-514)")
	void 관심사_일치_지점이_미일치_지점보다_앞에_배열된다() {
		// 미일치가 중심에 훨씬 가깝다 — 거리 첫 키(종전)면 미일치가 먼저라 여기서 깨진다.
		RouteCandidate 먼_일치 = 일치후보("먼 일치", 중심_LAT + 0.04, 중심_LNG);
		RouteCandidate 가까운_미일치 = 후보("가까운 미일치", 중심_LAT + 0.001, 중심_LNG);

		List<RouteCandidate> ordered = RouteOrderPlanner.order(
			List.of(가까운_미일치, 먼_일치), List.of(), null, 중심_LAT, 중심_LNG);

		assertThat(ordered).containsExactly(먼_일치, 가까운_미일치);
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("힌트 배치는 관심사 일치보다 우선한다 — 사용자가 순서 자체를 지목한 것이 더 강한 신호다")
	void 힌트_배치는_관심사_일치보다_우선한다() {
		RouteCandidate 일치 = 일치후보("맛집골목", 중심_LAT + 0.001, 중심_LNG);
		RouteCandidate 힌트지점 = 후보("광안리 해변", 중심_LAT + 0.04, 중심_LNG);

		List<RouteCandidate> ordered = RouteOrderPlanner.order(
			List.of(일치, 힌트지점), List.of("해변"), null, 중심_LAT, 중심_LNG);

		assertThat(ordered).containsExactly(힌트지점, 일치);
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("일치 지점끼리는 최근접 순서를 유지한다 — 일치 그룹 안에서는 종전 규칙 그대로다")
	void 일치_지점끼리는_최근접_순서를_유지한다() {
		RouteCandidate 가까운_일치 = 일치후보("가까운 일치", 중심_LAT + 0.01, 중심_LNG);
		RouteCandidate 먼_일치 = 일치후보("먼 일치", 중심_LAT + 0.03, 중심_LNG);
		RouteCandidate 미일치 = 후보("미일치", 중심_LAT + 0.002, 중심_LNG);

		List<RouteCandidate> ordered = RouteOrderPlanner.order(
			List.of(먼_일치, 미일치, 가까운_일치), List.of(), null, 중심_LAT, 중심_LNG);

		assertThat(ordered).containsExactly(가까운_일치, 먼_일치, 미일치);
	}

	// 검증: FR-ROUTE-11
	@Test
	@DisplayName("origin 이 없으면 힌트 배치의 마지막 지점에서 최근접 이웃을 시작한다")
	void 힌트가_있으면_그_마지막_지점이_시작점이다() {
		RouteCandidate 힌트지점 = 후보("빛축제", 중심_LAT + 0.04, 중심_LNG);
		RouteCandidate 힌트근처 = 후보("힌트 근처", 중심_LAT + 0.03, 중심_LNG);
		RouteCandidate 중심근처 = 후보("중심 근처", 중심_LAT, 중심_LNG + 0.001);

		List<RouteCandidate> ordered = RouteOrderPlanner.order(
			List.of(중심근처, 힌트근처, 힌트지점), List.of("빛축제"), null, 중심_LAT, 중심_LNG);

		// 뷰포트 중심 기준이면 중심근처가 먼저지만, 시작점이 힌트 지점이라 힌트근처가 먼저다.
		assertThat(ordered).containsExactly(힌트지점, 힌트근처, 중심근처);
	}
}
