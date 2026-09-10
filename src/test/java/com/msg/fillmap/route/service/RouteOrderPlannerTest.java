package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
		// ±0.02 배치 — 종전 ±0.04 는 보정 총거리 13,010m 로 도보 예산 절단(MSG-515)에 걸려 origin 효과 검증이
		// 안 된다. 좁혀서(총 7,228m) 절단 없이 시작점 효과만 본다.
		RouteCandidate 북쪽 = 후보("북쪽", 중심_LAT + 0.02, 중심_LNG);
		RouteCandidate 남쪽 = 후보("남쪽", 중심_LAT - 0.02, 중심_LNG);
		List<RouteCandidate> candidates = List.of(북쪽, 남쪽);

		// origin 이 없으면 뷰포트 중심 기준 — 등거리 동률이 아니게 남쪽을 살짝 가깝게 두지 않고 origin 효과만 본다.
		List<RouteCandidate> 남쪽출발 = RouteOrderPlanner.order(
			candidates, List.of(), new OriginDto(중심_LAT - 0.03, 중심_LNG), 중심_LAT, 중심_LNG);
		List<RouteCandidate> 북쪽출발 = RouteOrderPlanner.order(
			candidates, List.of(), new OriginDto(중심_LAT + 0.03, 중심_LNG), 중심_LAT, 중심_LNG);

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

	@Nested
	@DisplayName("도보 예산 절단 (MSG-515) — 보정 총 이동 거리 상한 10km")
	class WalkBudgetTrim {

		/** 위도 0.02° 간격 8곳 — 구간마다 보정 2,891m 라 네 지점째(누적 8,673m)까지 담기고 다섯 지점째에 넘는다. */
		private List<RouteCandidate> 넓은_배치_8곳() {
			List<RouteCandidate> candidates = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				candidates.add(후보("지점" + i, 35.16 + i * 0.02, 중심_LNG));
			}
			return candidates;
		}

		/** 결과 시퀀스를 방문 순서대로 걸은 1.3 × 하버사인 합 — 판정식과 같은 산술의 재계산이다. */
		private double 보정_총거리(List<RouteCandidate> sequence, OriginDto origin) {
			double total = 0;
			double lat = origin != null ? origin.lat() : sequence.getFirst().lat();
			double lng = origin != null ? origin.lng() : sequence.getFirst().lng();
			for (RouteCandidate candidate : sequence) {
				total += 1.3 * RouteOrderPlanner.distanceMeters(lat, lng, candidate.lat(), candidate.lng());
				lat = candidate.lat();
				lng = candidate.lng();
			}
			return total;
		}

		// 검증: FR-ROUTE-13
		@Test
		void 넓은_화면에서_보정_총거리가_상한을_넘지_않는다() {
			List<RouteCandidate> ordered = RouteOrderPlanner.order(
				넓은_배치_8곳(), List.of(), null, 중심_LAT, 중심_LNG);

			assertThat(ordered).hasSizeLessThan(8);
			assertThat(보정_총거리(ordered, null)).isLessThanOrEqualTo(10_000.0);
		}

		// 검증: FR-ROUTE-13
		@Test
		void 몰린_화면에서는_지점이_줄지_않고_종전과_같은_순서다() {
			// 위도 0.001° 간격 8곳 — 보정 총거리 약 1,012m 라 절단이 발동하지 않는다 (FR-3 무축소).
			List<RouteCandidate> 몰린_배치 = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				몰린_배치.add(후보("지점" + i, 35.151 + i * 0.001, 중심_LNG));
			}
			List<RouteCandidate> 뒤집힌_입력 = new ArrayList<>(몰린_배치.reversed());

			List<RouteCandidate> ordered = RouteOrderPlanner.order(뒤집힌_입력, List.of(), null, 중심_LAT, 중심_LNG);

			// 제약 도입 전 기대값 그대로 — 중심에서 최근접 이웃으로 이은 위도 오름차순 8곳 전부다.
			assertThat(ordered).containsExactlyElementsOf(몰린_배치);
		}

		// 검증: FR-ROUTE-13
		@Test
		@DisplayName("보정 총거리가 정확히 상한이면 잘리지 않는다 — 문면 \"넘지 않는다\"의 닫힌 상한")
		void 보정_총거리가_정확히_상한이면_잘리지_않는다() {
			double 경계위도 = 정확히_상한이_되는_위도();
			// GridEncoder 를 안 거치는 직접 생성 — 적도 좌표는 EPSG:5179 정의역 밖이고, 플래너는 순수 산술이라
			// gridId 는 동률 tie-break 에만 쓰인다 (지점 하나라 무의미).
			RouteCandidate 경계지점 = new RouteCandidate("경계지점", Kind.PLACE, 경계위도, 중심_LNG, "0_0",
				null, null, null, null, null, List.of());

			List<RouteCandidate> ordered = RouteOrderPlanner.order(
				List.of(경계지점), List.of(), new OriginDto(0.0, 중심_LNG), 0.0, 중심_LNG);

			// 열린 상한(>=)으로 구현했다면 정확히 10,000.0 인 이 지점이 잘려 여기서 깨진다.
			assertThat(ordered).containsExactly(경계지점);
		}

		/**
		 * 보정 거리(1.3 × 하버사인)가 정확히 10,000.0 이 되는 적도 기준 위도를 탐색한다 — 부동소수점에서 상수
		 * 등가 좌표는 해석적으로 못 박을 수 없어, 시드 근방을 ulp 단위로 걸어 이 플랫폼 산술의 등가점을 찾는다.
		 * 적도 기준인 이유: 위도 35도대는 좌표 ulp(≈7e-15도)가 거칠어 보정 거리가 약 400 ulp 씩 건너뛰므로
		 * 등가점이 표현 가능한 좌표에 잡히지 않는다 (실측). 플래너는 순수 산술이라 좌표가 국내일 필요가 없다.
		 */
		private double 정확히_상한이_되는_위도() {
			double seed = 0.06917858507067158;	// 10,000 / 1.3 / (지구 반지름 × π/180) — 해석적 근사
			for (int i = -3000; i <= 3000; i++) {
				double lat = seed + i * Math.ulp(seed);
				if (1.3 * RouteOrderPlanner.distanceMeters(0.0, 중심_LNG, lat, 중심_LNG) == 10_000.0) {
					return lat;
				}
			}
			throw new IllegalStateException("이 플랫폼 산술에는 보정 거리가 정확히 10,000.0 인 위도가 없다 — 픽스처 재설계 필요");
		}

		// 검증: FR-ROUTE-13
		@Test
		void origin이_있으면_출발지에서_첫_지점까지_거리도_예산에_든다() {
			RouteCandidate 가까운곳 = 후보("가까운곳", 중심_LAT, 중심_LNG);
			RouteCandidate 먼곳 = 후보("먼곳", 중심_LAT + 0.05, 중심_LNG);
			List<RouteCandidate> candidates = List.of(가까운곳, 먼곳);

			List<RouteCandidate> 근처출발 = RouteOrderPlanner.order(
				candidates, List.of(), new OriginDto(중심_LAT, 중심_LNG), 중심_LAT, 중심_LNG);
			List<RouteCandidate> 먼출발 = RouteOrderPlanner.order(
				candidates, List.of(), new OriginDto(중심_LAT - 0.03, 중심_LNG), 중심_LAT, 중심_LNG);

			// 같은 후보인데 origin→첫 지점 4,337m 가 예산에 들어 두 번째 지점(7,228m 추가)이 더 일찍 잘린다.
			assertThat(근처출발).containsExactly(가까운곳, 먼곳);
			assertThat(먼출발).containsExactly(가까운곳);
		}

		// 검증: FR-ROUTE-13
		@Test
		void origin에서_첫_지점부터_상한을_넘으면_빈_목록이다() {
			RouteCandidate 지점 = 후보("지점", 중심_LAT, 중심_LNG);

			List<RouteCandidate> ordered = RouteOrderPlanner.order(
				List.of(지점), List.of(), new OriginDto(중심_LAT - 0.1, 중심_LNG), 중심_LAT, 중심_LNG);

			// 최소 1지점 보장은 없다 — origin→첫 지점만으로 14,455m 라 전부 버려진다 (스펙 결정 2 엣지).
			assertThat(ordered).isEmpty();
		}

		// 검증: FR-ROUTE-13
		@Test
		void origin이_없으면_첫_지점은_항상_담긴다() {
			RouteCandidate 첫지점 = 후보("첫지점", 중심_LAT, 중심_LNG);
			RouteCandidate 아주먼곳 = 후보("아주먼곳", 중심_LAT + 0.2, 중심_LNG);

			List<RouteCandidate> ordered = RouteOrderPlanner.order(
				List.of(아주먼곳, 첫지점), List.of(), null, 중심_LAT, 중심_LNG);

			// 사용자는 첫 지점에서 시작하므로 첫 지점은 누적 0에서 담긴다 — 다음 지점(28,911m)만 잘린다.
			assertThat(ordered).containsExactly(첫지점);
		}

		// 검증: FR-ROUTE-13
		@Test
		void 힌트_지점도_누적_예산의_대상이다() {
			RouteCandidate 앞힌트 = 후보("알파거리", 중심_LAT, 중심_LNG);
			RouteCandidate 뒷힌트 = 후보("베타공원", 중심_LAT + 0.1, 중심_LNG);

			List<RouteCandidate> ordered = RouteOrderPlanner.order(
				List.of(뒷힌트, 앞힌트), List.of("알파거리", "베타공원"), null, 중심_LAT, 중심_LNG);

			// 힌트는 순서 신호지 예산 면제가 아니다 — 힌트 사이 14,455m 로 상한을 넘어 뒤쪽 힌트가 잘린다.
			assertThat(ordered).containsExactly(앞힌트);
		}

		// 검증: FR-ROUTE-13
		@Test
		void 축소가_발동해도_같은_입력이면_같은_출력이다() {
			List<RouteCandidate> candidates = 넓은_배치_8곳();

			List<RouteCandidate> first = RouteOrderPlanner.order(candidates, List.of(), null, 중심_LAT, 중심_LNG);
			List<RouteCandidate> second = RouteOrderPlanner.order(candidates, List.of(), null, 중심_LAT, 중심_LNG);

			assertThat(first).hasSizeLessThan(8);	// 절단이 실제로 발동한 픽스처다 (FR-5 는 절단 경로도 결정적)
			assertThat(second).isEqualTo(first);
		}
	}
}
