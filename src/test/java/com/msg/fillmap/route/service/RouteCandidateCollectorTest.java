package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.event.dto.EventOccurrenceChipResponseDto;
import com.msg.fillmap.event.service.EventQueryService;
import com.msg.fillmap.event.service.EventQueryService.LocationPoint;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.dto.MissionShape;
import com.msg.fillmap.mission.dto.MissionShape.BoxShape;
import com.msg.fillmap.mission.dto.MissionShape.LatLng;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.route.service.RouteCandidate.Kind;
import com.msg.fillmap.route.service.RouteIntentClient.ParsedIntent;
import com.msg.fillmap.search.dto.PlaceSearchResponseDto;
import com.msg.fillmap.search.exception.SearchErrorCode;
import com.msg.fillmap.search.service.PlaceSearchService;

/**
 * 후보 수집 규칙 검증 (MSG-457 §도메인 로직 1). 소스 조회(미션·행사·장소 검색)는 각자 패키지에서 검증된
 * 계약이라 mock 으로 두고, 이 테스트는 route 몫의 규칙만 고정한다 — 요청 시점 재필터, 뷰포트 안 대표 지점,
 * 해석 문자열의 후보 미생성, 검색 실패 삼킴, 상한 8. 스텁 없는 조회는 Mockito 기본값(빈 목록/맵)이다.
 */
@DisplayName("RouteCandidateCollector — 후보 수집 (미션·행사·장소 검색)")
class RouteCandidateCollectorTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);
	private static final ViewportBounds 뷰포트 = new ViewportBounds(35.05, 128.95, 35.25, 129.20);
	private static final ParsedIntent 빈해석 = new ParsedIntent(null, null, List.of(), List.of(), true);

	private final MissionQueryService missionQueryService = mock(MissionQueryService.class);
	private final EventQueryService eventQueryService = mock(EventQueryService.class);
	private final PlaceSearchService placeSearchService = mock(PlaceSearchService.class);
	private final GridQueryService gridQueryService = mock(GridQueryService.class);

	private final RouteCandidateCollector collector = new RouteCandidateCollector(
		missionQueryService, eventQueryService, placeSearchService, gridQueryService, new ObjectMapper(),
		new InterestMatcher(new ObjectMapper()),
		Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

	/* ---------- 픽스처 ---------- */

	private MissionResponseDto 미션(long id, MissionType type, String title,
		LocalDateTime startAt, LocalDateTime endAt, MissionShape shape) {
		return new MissionResponseDto(id, type.name(), title, null, startAt, endAt, shape,
			null, null, null, null, null, null, null, null);
	}

	/** 주어진 점을 중심으로 한 작은 판정 사각형 — 링 중점이 그 점이라 대표 좌표가 그 셀 중심이 된다. */
	private BoxShape 박스(double lat, double lng) {
		return new BoxShape(List.of(
			new LatLng(lat - 0.0004, lng - 0.0004),
			new LatLng(lat - 0.0004, lng + 0.0004),
			new LatLng(lat + 0.0004, lng + 0.0004),
			new LatLng(lat + 0.0004, lng - 0.0004),
			new LatLng(lat - 0.0004, lng - 0.0004)));
	}

	private MissionResponseDto 진행중_축제(long id, String title, double lat, double lng) {
		return 미션(id, MissionType.EVENT, title, NOW.minusDays(3), NOW.plusDays(3), 박스(lat, lng));
	}

	private PlaceSearchResponseDto 장소(String name, double lat, double lng) {
		return new PlaceSearchResponseDto(name, "부산 어딘가", lat, lng, GridEncoder.encode(lat, lng), null, null);
	}

	private EventOccurrenceChipResponseDto 진행중_행사(long id, String title) {
		return new EventOccurrenceChipResponseDto(id, title, "부산", NOW.minusDays(1), NOW.plusDays(1), "LIVE");
	}

	/* ---------- 시나리오 ---------- */

	// 검증: FR-ROUTE-04
	@Test
	@DisplayName("끝난 축제는 추천되지 않는다 — 낡은 스냅샷 캐시가 활성으로 내놓아도 요청 시점 재필터가 거른다")
	void 끝난_축제는_추천되지_않는다() {
		// 캐시 TTL 1시간(MSG-398 D1) 동안 종료가 반영되지 않은 상황 — 조회는 두 축제를 다 내놓는다.
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT)).willReturn(List.of(
			미션(1L, MissionType.EVENT, "끝난 축제", NOW.minusDays(10), NOW.minusHours(1), 박스(35.15, 129.05)),
			진행중_축제(2L, "진행 중 축제", 35.15, 129.08)));

		List<RouteCandidate> candidates = collector.collect(뷰포트, 빈해석);

		assertThat(candidates).extracting(RouteCandidate::name).containsExactly("진행 중 축제");
		assertThat(candidates.getFirst().kind()).isEqualTo(Kind.MISSION_FESTIVAL);
		assertThat(candidates.getFirst().missionId()).isEqualTo(2L);
	}

	// 검증: FR-ROUTE-06
	@Test
	@DisplayName("뷰포트 밖 행사 위치는 대표가 되지 않는다 — 안에 드는 첫 위치가 대표, 전부 밖이면 회차 제외")
	void 뷰포트_밖_행사_위치는_대표가_되지_않는다() {
		String 밖격자 = GridEncoder.encode(34.0, 125.0);
		String 안격자 = GridEncoder.encode(35.15, 129.05);
		given(eventQueryService.getOccurrencesInViewport(뷰포트))
			.willReturn(List.of(진행중_행사(1L, "흩어진 행사"), 진행중_행사(2L, "전부 밖 행사")));
		given(eventQueryService.getLocationsBulk(List.of(1L, 2L))).willReturn(Map.of(
			1L, List.of(new LocationPoint("정렬 첫 위치(밖)", 밖격자), new LocationPoint("안 위치", 안격자)),
			2L, List.of(new LocationPoint("밖 위치", 밖격자))));

		List<RouteCandidate> candidates = collector.collect(뷰포트, 빈해석);

		// 정렬 첫 위치가 밖이면 안에 드는 다음 위치가 대표다. 안에 위치가 없는 회차 2는 후보 자체가 없다.
		assertThat(candidates).hasSize(1);
		assertThat(candidates.getFirst().occurrenceId()).isEqualTo(1L);
		assertThat(candidates.getFirst().gridId()).isEqualTo(안격자);
		assertThat(candidates.getFirst().kind()).isEqualTo(Kind.EVENT);
	}

	// 검증: FR-ROUTE-03, NFR-SEC-08
	@Test
	@DisplayName("해석 결과의 장소 이름은 후보가 되지 않는다 — 관심사는 검색어 재료, 힌트는 순서 재료일 뿐")
	void 해석결과의_장소이름은_후보가_되지_않는다() {
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT))
			.willReturn(List.of(진행중_축제(1L, "빛축제", 35.15, 129.08)));
		// 미충족 관심사가 유발하는 검색은 "{region} {관심사}" 기계 조립 검색어의 집계 없는 오버로드 1회다.
		given(placeSearchService.searchPlaces("해운대 유령장소")).willReturn(List.of());
		ParsedIntent 해석 = new ParsedIntent("해운대", null, List.of("유령장소"), List.of("유령카페"), true);

		List<RouteCandidate> candidates = collector.collect(뷰포트, 해석);

		// 해석이 담은 문자열(유령장소·유령카페)로는 어떤 후보도 생기지 않는다 — 서버 조회 후보뿐이다.
		assertThat(candidates).extracting(RouteCandidate::name).containsExactly("빛축제");
		then(placeSearchService).should().searchPlaces("해운대 유령장소");
	}

	// 검증: FR-ROUTE-06
	@Test
	@DisplayName("빈 해석이면 뷰포트 기준으로 추천한다 — 전 후보 거리순, 장소 검색은 부르지 않는다")
	void 빈_해석이면_뷰포트_기준으로_추천한다() {
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT)).willReturn(List.of(
			진행중_축제(1L, "먼 축제", 35.24, 129.19),
			진행중_축제(2L, "가까운 축제", 35.15, 129.08)));

		List<RouteCandidate> candidates = collector.collect(뷰포트, 빈해석);

		assertThat(candidates).extracting(RouteCandidate::name).containsExactly("가까운 축제", "먼 축제");
		verifyNoInteractions(placeSearchService);
	}

	// 검증: FR-ROUTE-07
	@Test
	@DisplayName("장소 검색이 실패해도(5502) 미션·행사 후보로 성공한다 — 실패는 전파되지 않는다")
	void 장소검색이_실패해도_미션과_행사_후보로_성공한다() {
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT))
			.willReturn(List.of(진행중_축제(1L, "빛축제", 35.15, 129.08)));
		given(placeSearchService.searchPlaces("해운대 맛집"))
			.willThrow(new ApiException(SearchErrorCode.SEARCH_UPSTREAM_ERROR));
		ParsedIntent 해석 = new ParsedIntent("해운대", null, List.of("맛집"), List.of(), true);

		List<RouteCandidate> candidates = collector.collect(뷰포트, 해석);

		assertThat(candidates).extracting(RouteCandidate::name).containsExactly("빛축제");
	}

	// 검증: FR-ROUTE-13
	@Test
	void 지점_수는_상한_8을_넘지_않는다() {
		List<MissionResponseDto> 미션들 = new ArrayList<>(IntStream.rangeClosed(1, 10)
			.mapToObj(i -> 진행중_축제(i, "축제 " + i, 35.10 + i * 0.01, 129.05))
			.toList());
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT)).willReturn(미션들);

		assertThat(collector.collect(뷰포트, 빈해석)).hasSize(8);
	}

	// 검증: FR-ROUTE-04
	@Test
	@DisplayName("해석 기간 겹침은 앞뒤 2일 여유다 — 여유 안 후보는 남고 밖 후보는 빠진다")
	void 해석_기간_앞뒤_2일_여유_안의_후보만_남는다() {
		// 해석 기간 06-10~06-12, 여유 창 06-08~06-14. 두 축제 모두 요청 시점(06-01)엔 활성이다.
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT)).willReturn(List.of(
			미션(1L, MissionType.EVENT, "여유 안 축제", NOW.minusDays(3), NOW.plusDays(7), 박스(35.15, 129.05)),
			미션(2L, MissionType.EVENT, "여유 밖 축제", NOW.minusDays(3), NOW.plusDays(6), 박스(35.15, 129.08))));
		ParsedIntent 해석 = new ParsedIntent(null,
			new ParsedIntent.Period(NOW.toLocalDate().plusDays(9), NOW.toLocalDate().plusDays(11)),
			List.of(), List.of(), true);

		assertThat(collector.collect(뷰포트, 해석))
			.extracting(RouteCandidate::name).containsExactly("여유 안 축제");
	}

	// 검증: FR-ROUTE-18, FR-ROUTE-01
	@Test
	@DisplayName("선별 1순위는 관심사 일치다 — 먼 일치 후보가 가까운 불일치 후보보다 앞선다 (기존 유지 확인)")
	void 관심사_일치_후보가_선별에서_거리보다_앞선다() {
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT))
			.willReturn(List.of(진행중_축제(1L, "먼 빛축제", 35.24, 129.19)));
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.POPUP)).willReturn(List.of(
			미션(2L, MissionType.POPUP, "가까운 상점", NOW.minusDays(3), NOW.plusDays(3), 박스(35.15, 129.08))));
		ParsedIntent 해석 = new ParsedIntent(null, null, List.of("축제"), List.of(), true);

		assertThat(collector.collect(뷰포트, 해석))
			.extracting(RouteCandidate::name).containsExactly("먼 빛축제", "가까운 상점");
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("장소명에만 있는 관심사도 일치한다 — 미션 판정 재료에 placeName 이 신설됐다 (MSG-514)")
	void 장소명에만_있는_관심사도_일치한다() {
		// 제목·유형 라벨·소개문 어디에도 "해변"이 없다 — 장소명이 재료에 들어가야만 일치가 성립한다.
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT)).willReturn(List.of(
			new MissionResponseDto(1L, MissionType.EVENT.name(), "여름 문화 주간", null,
				NOW.minusDays(3), NOW.plusDays(3), 박스(35.15, 129.08),
				null, "송정해변 특설무대", null, null, null, null, null, null)));
		ParsedIntent 해석 = new ParsedIntent(null, null, List.of("해변"), List.of(), true);

		List<RouteCandidate> candidates = collector.collect(뷰포트, 해석);

		assertThat(candidates).hasSize(1);
		assertThat(candidates.getFirst().matchedInterest()).isEqualTo("해변");
		verifyNoInteractions(placeSearchService);	// 일치로 채워져 미충족 검색이 유발되지 않는다
	}

	// 검증: FR-ROUTE-18, FR-ROUTE-03
	@Test
	@DisplayName("판정이 넓어져도 서버가 확인하지 않은 장소는 후보가 되지 않는다 (FR-2, FR-ROUTE-03 유지 확인)")
	void 판정이_넓어져도_서버가_확인하지_않은_장소는_후보가_되지_않는다() {
		// 사전 경유 판정이 있어도 미충족 관심사의 경로는 종전 그대로다 — 검색 실조회가 빈 결과면 후보도 없다.
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT))
			.willReturn(List.of(진행중_축제(1L, "빛축제", 35.15, 129.08)));
		given(placeSearchService.searchPlaces("해운대 맛집")).willReturn(List.of());
		ParsedIntent 해석 = new ParsedIntent("해운대", null, List.of("맛집"), List.of(), true);

		List<RouteCandidate> candidates = collector.collect(뷰포트, 해석);

		// "맛집"은 음식 근거어가 없는 빛축제와 이어지지 않고(사전 규율), 해석 문자열이 후보를 만들지도 않는다.
		assertThat(candidates).extracting(RouteCandidate::name).containsExactly("빛축제");
		assertThat(candidates.getFirst().matchedInterest()).isNull();
		then(placeSearchService).should().searchPlaces("해운대 맛집");
	}

	// 검증: FR-ROUTE-06
	@Test
	@DisplayName("대표 좌표가 뷰포트 밖인 미션은 제외된다 — 사각형 겹침만으로 잡힌 큰 축제(행사 규칙과 동일)")
	void 대표_좌표가_뷰포트_밖인_미션은_제외된다() {
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT))
			.willReturn(List.of(진행중_축제(1L, "중심이 밖인 축제", 35.30, 129.05)));

		assertThat(collector.collect(뷰포트, 빈해석)).isEmpty();
	}

	// 검증: FR-ROUTE-06
	@Test
	@DisplayName("뷰포트 밖 장소 검색 결과는 후보가 되지 않는다 — 거른 뒤 상위 3건이라 자리도 뺏지 않는다")
	void 뷰포트_밖_장소_검색_결과는_후보가_되지_않는다() {
		// 카카오 키워드 검색은 지역 제한이 없다 — 첫 결과가 뷰포트 밖이어도 안 결과 3건이 그대로 후보가 된다.
		given(placeSearchService.searchPlaces("해운대 맛집")).willReturn(List.of(
			장소("먼 맛집", 34.0, 125.0),
			장소("안 맛집1", 35.15, 129.05),
			장소("안 맛집2", 35.15, 129.06),
			장소("안 맛집3", 35.15, 129.07)));
		ParsedIntent 해석 = new ParsedIntent("해운대", null, List.of("맛집"), List.of(), true);

		assertThat(collector.collect(뷰포트, 해석))
			.extracting(RouteCandidate::name)
			.containsExactlyInAnyOrder("안 맛집1", "안 맛집2", "안 맛집3");
	}

	// 검증: FR-ROUTE-04
	@Test
	@DisplayName("기간 경계는 KST 날짜로 판정한다 — UTC 저녁 종료(KST 자정 직후)가 전날로 오판정되지 않는다")
	void 기간_경계는_KST_날짜로_판정한다() {
		// 해석 기간 06-10~06-12(KST) → 여유 창 06-08~06-14. 종료가 06-07T20:00 UTC = KST 06-08 05:00 이라
		// KST 날짜로는 창의 첫날이다 — UTC 날짜(06-07)로 비교하면 하루 이르게 잘려 나간다.
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT)).willReturn(List.of(
			미션(1L, MissionType.EVENT, "자정 직후 종료 축제", NOW.minusDays(3),
				NOW.plusDays(6).plusHours(20), 박스(35.15, 129.05))));
		ParsedIntent 해석 = new ParsedIntent(null,
			new ParsedIntent.Period(NOW.toLocalDate().plusDays(9), NOW.toLocalDate().plusDays(11)),
			List.of(), List.of(), true);

		assertThat(collector.collect(뷰포트, 해석))
			.extracting(RouteCandidate::name).containsExactly("자정 직후 종료 축제");
	}

	// 검증: FR-ROUTE-03
	@Test
	@DisplayName("빈 문자열 관심사는 장소 검색을 유발하지 않는다 — region 단독 광역 검색 차단")
	void 빈_문자열_관심사는_장소_검색을_유발하지_않는다() {
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.EVENT))
			.willReturn(List.of(진행중_축제(1L, "빛축제", 35.15, 129.08)));
		ParsedIntent 해석 = new ParsedIntent("부산", null, List.of(" "), List.of(), true);

		assertThat(collector.collect(뷰포트, 해석)).extracting(RouteCandidate::name).containsExactly("빛축제");
		verifyNoInteractions(placeSearchService);
	}

	// 검증: FR-ROUTE-02
	@Test
	@DisplayName("코스 대표 좌표는 PATH 시작점이다 — line 첫 좌표([lon, lat])를 그대로 쓴다")
	void 코스_대표_좌표는_PATH_시작점이다() {
		PathShape path = new PathShape(
			"{\"type\": \"LineString\", \"coordinates\": [[129.05, 35.16], [129.06, 35.17]]}", List.of());
		given(missionQueryService.getMissionsInViewport(뷰포트, MissionType.COURSE))
			.willReturn(List.of(미션(1L, MissionType.COURSE, "해안 코스", null, null, path)));

		List<RouteCandidate> candidates = collector.collect(뷰포트, 빈해석);

		assertThat(candidates).hasSize(1);
		assertThat(candidates.getFirst().kind()).isEqualTo(Kind.MISSION_COURSE);
		assertThat(candidates.getFirst().lat()).isEqualTo(35.16);
		assertThat(candidates.getFirst().lng()).isEqualTo(129.05);
		assertThat(candidates.getFirst().gridId()).isEqualTo(GridEncoder.encode(35.16, 129.05));
	}
}
