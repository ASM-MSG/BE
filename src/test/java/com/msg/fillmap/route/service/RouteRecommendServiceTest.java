package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.event.service.EventQueryService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.dto.MissionShape;
import com.msg.fillmap.mission.dto.MissionShape.BoxShape;
import com.msg.fillmap.mission.dto.MissionShape.LatLng;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionQueryService.MentionedRegionMatch;
import com.msg.fillmap.route.config.RouteAiProperties;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto.OriginDto;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto.ViewportDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto.MentionedAreaDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto.RoutePointDto;
import com.msg.fillmap.route.exception.RouteErrorCode;
import com.msg.fillmap.route.service.RouteCandidate.Kind;
import com.msg.fillmap.search.service.PlaceSearchService;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * 추천 플로우 통합 검증 (MSG-457 §도메인 로직). AI 두 호출(parse·explain)은 MockRestServiceServer 스텁,
 * 후보 수집은 mock(수집 규칙은 RouteCandidateCollectorTest 가 고정), 표시명 리졸버는 실물이다.
 * 요청 제한·캐시가 시각에 걸려 있어 전진 가능한 SteppingClock 을 주입한다 — 실제 대기 없이 창을 넘는다.
 * 쓰기 의존(repository·커맨드 서비스)은 아예 주입되지 않는 구조라 스탬프 불변은 구조로도 보장된다.
 */
@DisplayName("RouteRecommendService — 추천 플로우 통합 (AI 스텁)")
class RouteRecommendServiceTest {

	private static final long USER_ID = 42L;
	private static final String BASE_URL = "https://route-ai.test";
	private static final String PARSE_URL = BASE_URL + "/route/parse";
	private static final String EXPLAIN_URL = BASE_URL + "/route/explain";
	private static final ViewportDto 뷰포트 = new ViewportDto(35.05, 128.95, 35.25, 129.20);
	private static final String 빈_해석_응답 =
		"{\"region\": null, \"period\": null, \"interests\": [], \"preferred_order\": [], \"related\": true}";

	private final RouteCandidateCollector collector = mock(RouteCandidateCollector.class);
	private final ZoneNameQueryService zoneNameQueryService = mock(ZoneNameQueryService.class);
	private final GridQueryService gridQueryService = mock(GridQueryService.class);
	// 언급 지역 신호(MSG-468)의 데이터 출처 두 계약은 mock, 판정 합성(리졸버)은 실물이다 — 기본 스텁(빈 목록)은
	// 대조 실패라 기존 시나리오는 전부 무신호로 흐른다.
	private final ZoneQueryService zoneQueryService = mock(ZoneQueryService.class);
	private final RegionQueryService regionQueryService = mock(RegionQueryService.class);
	// MSG-514 실물 수집 시나리오용 — 수집기 안 조회 계약 mock. 기본 스텁(빈 목록)이라 스텁한 유형만 후보가 된다.
	private final MissionQueryService missionQueryService = mock(MissionQueryService.class);
	private final EventQueryService eventQueryService = mock(EventQueryService.class);
	private final PlaceSearchService placeSearchService = mock(PlaceSearchService.class);
	private final SteppingClock clock = new SteppingClock();

	private MockRestServiceServer server;
	private RouteIntentClient intentClient;
	private RouteRecommendService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		intentClient = new RouteIntentClient(builder,
			new RouteAiProperties(true, BASE_URL, Duration.ofSeconds(10)));

		given(zoneNameQueryService.resolver()).willReturn(new ZoneNameResolver(List.of()));
		service = new RouteRecommendServiceImpl(intentClientProvider(), collector, zoneNameQueryService,
			gridQueryService, new RouteMentionedAreaResolver(zoneQueryService, regionQueryService), clock);
	}

	private ObjectProvider<RouteIntentClient> intentClientProvider() {
		@SuppressWarnings("unchecked")
		ObjectProvider<RouteIntentClient> provider = mock(ObjectProvider.class);
		given(provider.getIfAvailable()).willReturn(intentClient);
		return provider;
	}

	/**
	 * 실물 수집기(실물 InterestMatcher — 배포 사전 그대로)로 조립한 서비스 — 해석부터 판정·조립까지 관통하는
	 * 시나리오(MSG-514)용. AI 스텁·시계는 공유하고, 수집기 안 조회 계약만 mock 이다.
	 */
	private RouteRecommendService 실수집_서비스() {
		RouteCandidateCollector realCollector = new RouteCandidateCollector(missionQueryService, eventQueryService,
			placeSearchService, gridQueryService, new ObjectMapper(), new InterestMatcher(new ObjectMapper()), clock);
		return new RouteRecommendServiceImpl(intentClientProvider(), realCollector, zoneNameQueryService,
			gridQueryService, new RouteMentionedAreaResolver(zoneQueryService, regionQueryService), clock);
	}

	/* ---------- 픽스처 ---------- */

	private RouteRecommendRequestDto 요청() {
		return new RouteRecommendRequestDto("부산역 내려서 해운대 축제 보고 싶어", 뷰포트, null);
	}

	private RouteRecommendRequestDto 문장요청(String text) {
		return new RouteRecommendRequestDto(text, 뷰포트, null);
	}

	/** 주어진 점을 중심으로 한 작은 판정 사각형 (RouteCandidateCollectorTest 와 동일 픽스처). */
	private BoxShape 박스(double lat, double lng) {
		return new BoxShape(List.of(
			new LatLng(lat - 0.0004, lng - 0.0004),
			new LatLng(lat - 0.0004, lng + 0.0004),
			new LatLng(lat + 0.0004, lng + 0.0004),
			new LatLng(lat + 0.0004, lng - 0.0004),
			new LatLng(lat - 0.0004, lng - 0.0004)));
	}

	/** 상시(무기간) 축제 미션 — SteppingClock 어느 시점에도 활성이라 시각 픽스처가 필요 없다. */
	private MissionResponseDto 상시_축제미션(long id, String title, String description, double lat, double lng) {
		return new MissionResponseDto(id, MissionType.EVENT.name(), title, null, null, null, 박스(lat, lng),
			description, null, null, null, null, null, null, null);
	}

	private void parse는_관심사를_준다(String interest) {
		server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(
			"{\"region\": null, \"period\": null, \"interests\": [\"" + interest
				+ "\"], \"preferred_order\": [], \"related\": true}",
			MediaType.APPLICATION_JSON));
	}

	private RouteCandidate 장소후보(String name, double lat, double lng) {
		return new RouteCandidate(name, Kind.PLACE, lat, lng, GridEncoder.encode(lat, lng),
			null, null, null, null, null, List.of());
	}

	private void parse는_빈해석을_준다() {
		server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(빈_해석_응답, MediaType.APPLICATION_JSON));
	}

	/** 승격(MSG-540) 후 summary 는 필수라 기본 요약을 실어 위임한다 — 요약 값을 보는 테스트는 아래 헬퍼를 쓴다. */
	private void explain은_이유를_준다(String... reasons) {
		explain은_요약과_이유를_준다("동선 요약", reasons);
	}

	/** 신계약 explain 스텁 (MSG-539) — reasons 에 동선 전체의 종합 이유 summary 가 함께 실린 응답. */
	private void explain은_요약과_이유를_준다(String summary, String... reasons) {
		String body = "{\"reasons\": [" + String.join(", ",
			List.of(reasons).stream().map(reason -> "\"" + reason + "\"").toList())
			+ "], \"summary\": \"" + summary + "\"}";
		server.expect(requestTo(EXPLAIN_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	/* ---------- 시나리오 ---------- */

	@Nested
	@DisplayName("정상 플로우")
	class Happy {

		// 검증: FR-ROUTE-01, FR-ROUTE-02
		@Test
		@DisplayName("자연어와 뷰포트로 순서 있는 지점 목록을 받는다 — 이름·좌표·격자·표시명 재료·연결 id 포함")
		void 자연어와_뷰포트로_순서있는_지점목록을_받는다() {
			// 중심(35.15, 129.075)에서 거리가 단조 증가하도록 배치 — 최근접 이웃 순서가 축제→행사→장소로 고정된다.
			RouteCandidate 축제 = new RouteCandidate("해운대 빛축제", Kind.MISSION_FESTIVAL, 35.15, 129.08,
				GridEncoder.encode(35.15, 129.08), 12L, null,
				LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0), "축제", List.of());
			RouteCandidate 행사 = new RouteCandidate("불꽃축제", Kind.EVENT, 35.16, 129.09,
				GridEncoder.encode(35.16, 129.09), null, 7L, null, null, null, List.of());
			RouteCandidate 장소 = 장소후보("맛집", 35.17, 129.10);
			given(collector.collect(any(), any())).willReturn(List.of(축제, 행사, 장소));
			given(gridQueryService.resolveRegionNames(any())).willReturn(Map.of(축제.gridId(), "우동"));
			given(zoneNameQueryService.resolver()).willReturn(new ZoneNameResolver(List.of(구역(축제.gridId()))));
			parse는_빈해석을_준다();
			explain은_이유를_준다("축제 이유", "행사 이유", "장소 이유");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.points()).extracting(
					RoutePointDto::order, RoutePointDto::name, RoutePointDto::kind,
					RoutePointDto::missionId, RoutePointDto::occurrenceId)
				.containsExactly(
					tuple(1, "해운대 빛축제", "MISSION_FESTIVAL", 12L, null),
					tuple(2, "불꽃축제", "EVENT", null, 7L),
					tuple(3, "맛집", "PLACE", null, null));
			RoutePointDto 첫지점 = response.points().getFirst();
			assertThat(첫지점.lat()).isEqualTo(35.15);
			assertThat(첫지점.gridId()).isEqualTo(축제.gridId());
			assertThat(첫지점.zoneName()).isEqualTo("해운대");
			assertThat(첫지점.zoneCell()).isEqualTo("A-1");
			assertThat(첫지점.regionName()).isEqualTo("우동");
			assertThat(response.notice()).isNull();	// 3개 이상 — 안내 없음
			server.verify();
		}

		// 검증: FR-ROUTE-05, FR-ROUTE-20, AC-539-07 (지점별 이유는 변경 전과 같다 — 기존 테스트 유지로 갈음)
		@Test
		void 지점마다_추천_이유가_실린다() {
			given(collector.collect(any(), any()))
				.willReturn(List.of(장소후보("카페", 35.15, 129.08), 장소후보("서점", 35.16, 129.09)));
			parse는_빈해석을_준다();
			explain은_이유를_준다("조용해서 쉬기 좋아요.", "걸어서 이어집니다.");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.points()).extracting(RoutePointDto::reason)
				.containsExactly("조용해서 쉬기 좋아요.", "걸어서 이어집니다.");
		}

		// 검증: FR-ROUTE-05, NFR-SEC-09
		@Test
		@DisplayName("facts 는 출처 상시 1건이 하한 1을 보장하고, 직전 거리 항목이 없다 (FR-ROUTE-05 개정, MSG-483)")
		void 추천_이유_사실_문장에_직전_거리_항목이_실리지_않는다() {
			RouteCandidate 카페 = 장소후보("카페", 35.15, 129.08);
			RouteCandidate 서점 = 장소후보("서점", 35.16, 129.09);
			given(collector.collect(any(), any())).willReturn(List.of(카페, 서점));
			parse는_빈해석을_준다();
			// strict 비교 — 기간·관심사가 없는 후보도 출처 문장 1건이 보장되고(하한 1), 여분 필드가 없다.
			// 둘째 지점에 "이전 지점에서 X.Xkm" 가 실리면 여기서 깨진다 — 직전 거리 제거의 관측 지점.
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(content().json("""
					{"text": "부산역 내려서 해운대 축제 보고 싶어",
					 "points": [
						{"name": "카페", "kind": "place", "facts": ["장소 검색 결과"]},
						{"name": "서점", "kind": "place", "facts": ["장소 검색 결과"]}]}
					""", JsonCompareMode.STRICT))
				.andRespond(withSuccess("{\"reasons\": [\"r1\", \"r2\"], \"summary\": \"동선 요약\"}",
					MediaType.APPLICATION_JSON));

			service.recommend(USER_ID, 요청());

			server.verify();
		}

		// 검증: FR-ROUTE-05
		@Test
		@DisplayName("facts 기간은 KST 날짜로 표기된다 — 시더의 전날 15:00 UTC 저장이 전날 날짜로 새지 않는다")
		void facts_기간은_KST_날짜로_표기된다() {
			// KST 8월 1일 00:00 ~ 8월 31일 23:59 를 UTC 로 저장한 값 — UTC 날짜 그대로면 "2026-07-31~"이 된다.
			given(collector.collect(any(), any())).willReturn(List.of(new RouteCandidate(
				"빛축제", Kind.MISSION_FESTIVAL, 35.15, 129.08, GridEncoder.encode(35.15, 129.08), 12L, null,
				LocalDateTime.of(2026, 7, 31, 15, 0), LocalDateTime.of(2026, 8, 31, 14, 59), null, List.of())));
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(jsonPath("$.points[0].facts[1]", is("2026-08-01~2026-08-31 진행 중")))
				.andRespond(withSuccess("{\"reasons\": [\"r1\"], \"summary\": \"동선 요약\"}",
					MediaType.APPLICATION_JSON));

			service.recommend(USER_ID, 요청());

			server.verify();
		}

		// 검증: FR-ROUTE-05
		@Test
		@DisplayName("200자 제목 미션이 후보여도 성공한다 — explain 요청 name 은 100자 절단, 응답 name 은 원문")
		void 이백자_제목_미션이_후보여도_성공한다() {
			String 제목 = "가".repeat(200);
			given(collector.collect(any(), any())).willReturn(List.of(new RouteCandidate(
				제목, Kind.MISSION_FESTIVAL, 35.15, 129.08, GridEncoder.encode(35.15, 129.08),
				12L, null, null, null, null, List.of())));
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(jsonPath("$.points[0].name", is(제목.substring(0, 100))))
				.andRespond(withSuccess("{\"reasons\": [\"r1\"], \"summary\": \"동선 요약\"}",
					MediaType.APPLICATION_JSON));

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.points().getFirst().name()).isEqualTo(제목);
			server.verify();
		}

		// 검증: FR-ROUTE-09
		@Test
		@DisplayName("추천만으로 스탬프가 발급되지 않는다 — 쓰기 의존이 없고 후보 수집 읽기 1회가 전부다")
		void 추천만으로_스탬프가_발급되지_않는다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 35.15, 129.08)));
			parse는_빈해석을_준다();
			explain은_이유를_준다("r1");

			service.recommend(USER_ID, 요청());

			// 저장 의존(repository·커맨드 서비스)은 주입 자체가 없다 — 도메인 접점인 수집도 읽기 호출 1회뿐이다.
			then(collector).should(only()).collect(any(), any());
		}
	}

	@Nested
	@DisplayName("후보 부족 (FR-ROUTE-07)")
	class Insufficient {

		// 검증: FR-ROUTE-07
		@Test
		void 후보가_부족하면_찾은_만큼과_안내가_온다() {
			given(collector.collect(any(), any()))
				.willReturn(List.of(장소후보("카페", 35.15, 129.08), 장소후보("서점", 35.16, 129.09)));
			parse는_빈해석을_준다();
			explain은_이유를_준다("r1", "r2");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.points()).hasSize(2);	// 실패가 아니라 찾은 만큼의 성공이다
			assertThat(response.notice()).isNotNull();
		}

		// 검증: FR-ROUTE-07
		@Test
		@DisplayName("후보가 없으면 빈 목록과 안내다 — explain 은 부르지 않는다 (기대 0회)")
		void 후보가_없으면_빈_목록과_안내다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			parse는_빈해석을_준다();	// explain 기대는 걸지 않는다 — 나갔다면 verify 가 실패한다

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.points()).isEmpty();
			assertThat(response.notice()).isNotNull();
			server.verify();
		}
	}

	@Nested
	@DisplayName("notice 문구 회귀 (MSG-487 결정 2)")
	class NoticeWording {

		// 검증: FR-ROUTE-07
		@Test
		void 후보가_없으면_지역_이동을_제안하지_않는_새_문구가_실린다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			parse는_빈해석을_준다();

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.notice())
				.isEqualTo("조건에 맞는 곳을 찾지 못했어요. 문장을 바꾸거나 다른 지역에서 다시 짜 보세요.");
		}

		// 검증: FR-ROUTE-07
		@Test
		@DisplayName("후보가 두 곳이면 개수를 담은 문구가 실린다 — 피그마 시안 배너 실측과 글자 단위 일치")
		void 후보가_두_곳이면_개수를_담은_문구가_실린다() {
			given(collector.collect(any(), any()))
				.willReturn(List.of(장소후보("카페", 35.15, 129.08), 장소후보("서점", 35.16, 129.09)));
			parse는_빈해석을_준다();
			explain은_이유를_준다("r1", "r2");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.notice())
				.isEqualTo("조건에 맞는 곳을 2곳만 찾았어요. 문장을 바꾸거나 다른 지역에서 다시 짜 보세요.");
		}

		// 검증: FR-ROUTE-07
		@Test
		void 후보가_한_곳이면_한_곳으로_읽히는_문구가_실린다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 35.15, 129.08)));
			parse는_빈해석을_준다();
			explain은_이유를_준다("r1");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.notice())
				.isEqualTo("조건에 맞는 곳을 1곳만 찾았어요. 문장을 바꾸거나 다른 지역에서 다시 짜 보세요.");
		}

		// 검증: FR-ROUTE-07
		@Test
		void 후보가_세_곳_이상이면_안내가_없다() {
			given(collector.collect(any(), any())).willReturn(List.of(
				장소후보("카페", 35.15, 129.08), 장소후보("서점", 35.16, 129.09), 장소후보("맛집", 35.17, 129.10)));
			parse는_빈해석을_준다();
			explain은_이유를_준다("r1", "r2", "r3");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.notice()).isNull();
		}
	}

	@Nested
	@DisplayName("AI 실패와 요청 제한")
	class Failure {

		// 검증: FR-ROUTE-08
		@Test
		void AI_실패는_결과를_지어내지_않고_실패로_끝난다() {
			server.expect(requestTo(PARSE_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

			assertThatThrownBy(() -> service.recommend(USER_ID, 요청()))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_AI_UNAVAILABLE);
		}

		// 검증: FR-ROUTE-12
		@Test
		void 짧은_간격_반복_요청은_거부된다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			parse는_빈해석을_준다();
			service.recommend(USER_ID, 요청());
			clock.advance(Duration.ofSeconds(5));

			assertThatThrownBy(() -> service.recommend(USER_ID, 요청()))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_RATE_LIMITED);

			// 거부된 시도(t=5)는 창을 늘리지 않는다 — 창이 t=5 로 밀렸다면 t=11 도 거부돼 여기서 깨진다.
			clock.advance(Duration.ofSeconds(6));
			assertThatCode(() -> service.recommend(USER_ID, 요청())).doesNotThrowAnyException();
			server.verify();	// 거부된 시도는 parse 를 부르지 않았고, t=11 성공은 캐시 히트라 parse 는 여전히 1회다
		}

		// 검증: FR-ROUTE-12
		@Test
		@DisplayName("실패한 시도도 요청 제한 창을 소모한다 — 외부 비용은 이미 나갔으므로 되돌리지 않는다")
		void 실패한_시도도_요청_제한_창을_소모한다() {
			server.expect(requestTo(PARSE_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
			assertThatThrownBy(() -> service.recommend(USER_ID, 요청()))
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_AI_UNAVAILABLE);
			clock.advance(Duration.ofSeconds(5));

			assertThatThrownBy(() -> service.recommend(USER_ID, 요청()))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_RATE_LIMITED);
		}
	}

	@Nested
	@DisplayName("해석 캐시 (FR-ROUTE-10)")
	class ParseCache {

		// 검증: FR-ROUTE-10
		@Test
		@DisplayName("같은 요청 재전송은 parse 를 다시 사지 않고 같은 결과를 준다 — 캐시 창 안 재요청")
		void 같은_요청_재전송은_parse를_다시_사지_않고_같은_결과를_준다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 35.15, 129.08)));
			parse는_빈해석을_준다();	// parse 기대는 한 번뿐 — 둘째 요청이 parse 를 사면 verify 가 실패한다
			explain은_이유를_준다("r1");
			explain은_이유를_준다("r1");

			RouteRecommendResponseDto first = service.recommend(USER_ID, 요청());
			clock.advance(Duration.ofSeconds(10));	// 요청 제한 창은 넘고 캐시 TTL(10분) 안이다
			RouteRecommendResponseDto second = service.recommend(USER_ID, 요청());

			assertThat(second).isEqualTo(first);
			server.verify();
		}
	}

	@Nested
	@DisplayName("종합 추천 이유 (MSG-539 FR-ROUTE-20) — 지점이 실리면 summary, 빈 목록이면 null")
	class Summary {

		// 검증: FR-ROUTE-20, AC-539-01
		@Test
		@DisplayName("정상 추천 응답에 종합 이유가 실린다 — explain 스텁의 summary 그대로, 지점별 reason 은 종전대로")
		void 정상_추천_응답에_종합_이유가_실린다() {
			given(collector.collect(any(), any())).willReturn(List.of(
				장소후보("카페", 35.15, 129.08), 장소후보("서점", 35.16, 129.09), 장소후보("맛집", 35.17, 129.10)));
			parse는_빈해석을_준다();
			explain은_요약과_이유를_준다("화면 범위의 세 곳을 걷기 좋은 순서로 묶었어요.", "r1", "r2", "r3");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.summary()).isEqualTo("화면 범위의 세 곳을 걷기 좋은 순서로 묶었어요.");
			assertThat(response.points()).extracting(RoutePointDto::reason).containsExactly("r1", "r2", "r3");
			assertThat(response.notice()).isNull();	// 정상 추천 — summary 가 문자열, notice 는 null
		}

		// 검증: FR-ROUTE-20, AC-539-01
		@Test
		@DisplayName("부족 안내 응답에도 지점이 있으면 종합 이유가 실린다 — 부족 notice 와 summary 공존")
		void 부족_안내_응답에도_지점이_있으면_종합_이유가_실린다() {
			given(collector.collect(any(), any()))
				.willReturn(List.of(장소후보("카페", 35.15, 129.08), 장소후보("서점", 35.16, 129.09)));
			parse는_빈해석을_준다();
			explain은_요약과_이유를_준다("가까운 두 곳만 찾아 묶었어요.", "r1", "r2");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.points()).hasSize(2);
			assertThat(response.notice()).isNotNull();	// 부족 안내와 공존한다
			assertThat(response.summary()).isEqualTo("가까운 두 곳만 찾아 묶었어요.");
		}

		// 검증: FR-ROUTE-20, AC-539-05
		@Test
		@DisplayName("빈 목록 세 갈래는 종합 이유가 null 이다 — 빈 후보·무관 문장·절단 소진 모두 explain 미호출")
		void 빈_목록_세_갈래는_종합_이유가_null이다() {
			// 기대는 전부 선등록한다 (MockRestServiceServer 는 첫 실요청 뒤 추가 불가). explain 기대는 세 갈래
			// 모두 걸지 않는다 — 나갔다면 verify 가 실패한다. 수집은 갈래 1 빈 목록 → 갈래 3 절단 대상 순이고
			// (무관 갈래는 수집 미도달), parse 는 갈래 순서대로 빈 해석 → 무관 → 빈 해석이다.
			given(collector.collect(any(), any()))
				.willReturn(List.of())
				.willReturn(List.of(장소후보("카페", 37.65, 127.10)));
			parse는_빈해석을_준다();
			server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(
				"{\"region\": null, \"period\": null, \"interests\": [], \"preferred_order\": [],"
					+ " \"related\": false}",
				MediaType.APPLICATION_JSON));
			parse는_빈해석을_준다();

			// 갈래 1 — 빈 후보 (FR-ROUTE-07)
			RouteRecommendResponseDto 빈후보 = service.recommend(USER_ID, 문장요청("이 근처 축제 보고 싶어"));
			// 갈래 2 — 무관 문장 (FR-ROUTE-19)
			clock.advance(Duration.ofSeconds(10));
			RouteRecommendResponseDto 무관 = service.recommend(USER_ID, 문장요청("롤 정글 동선 짜 줘"));
			// 갈래 3 — 도보 절단 소진 (FR-ROUTE-13): origin→유일 후보가 직선 약 31km 라 첫 구간부터 동선이 빈다
			clock.advance(Duration.ofSeconds(10));
			RouteRecommendResponseDto 절단소진 = service.recommend(USER_ID, new RouteRecommendRequestDto(
				"서울 축제 보고 싶어", new ViewportDto(37.45, 126.85, 37.65, 127.10), new OriginDto(37.45, 126.85)));

			assertThat(빈후보.summary()).isNull();
			assertThat(무관.summary()).isNull();
			assertThat(절단소진.summary()).isNull();
			assertThat(빈후보.notice()).isNotNull();	// notice 가 summary 의 자리를 맡는다
			server.verify();
		}

		// 검증: FR-ROUTE-20, AC-539-06
		@Test
		@DisplayName("빈 해석이어도 지점이 실리면 종합 이유가 온다 — 뷰포트 기준 추천도 summary 경로가 같다")
		void 빈_해석이어도_지점이_실리면_종합_이유가_온다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 35.15, 129.08)));
			parse는_빈해석을_준다();	// related=true, 전 필드 빈 해석 (FR-ROUTE-06)
			explain은_요약과_이유를_준다("지금 보는 화면 범위에서 골랐어요.", "r1");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.points()).hasSize(1);
			assertThat(response.summary()).isEqualTo("지금 보는 화면 범위에서 골랐어요.");
		}

		// 검증: FR-ROUTE-20, AC-539-09
		@Test
		@DisplayName("캐시 창 안 재요청에도 종합 이유가 새로 실린다 — parse 는 1회, explain 은 2회 (summary 비캐시)")
		void 캐시_창_안_재요청에도_종합_이유가_새로_실린다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 35.15, 129.08)));
			parse는_빈해석을_준다();	// parse 기대는 한 번뿐 — 둘째 요청이 parse 를 사면 verify 가 실패한다
			explain은_요약과_이유를_준다("첫 요약입니다.", "r1");
			explain은_요약과_이유를_준다("둘째 요약입니다.", "r1");

			RouteRecommendResponseDto first = service.recommend(USER_ID, 요청());
			clock.advance(Duration.ofSeconds(10));	// 요청 제한 창은 넘고 캐시 TTL(10분) 안이다
			RouteRecommendResponseDto second = service.recommend(USER_ID, 요청());

			// 값이 다른 두 요약이 각자 실렸다 — explain 이 캐시 없이 요청마다 새로 나간 관측 지점이다 (결정 5).
			assertThat(first.summary()).isEqualTo("첫 요약입니다.");
			assertThat(second.summary()).isEqualTo("둘째 요약입니다.");
			server.verify();
		}
	}

	@Nested
	@DisplayName("무관 문장 (MSG-513 FR-ROUTE-19) — related=false 소비")
	class Unrelated {

		private static final String 무관_안내 =
			"장소 방문 동선을 짜 드리는 기능이에요. 가고 싶은 지역이나 관심사를 문장에 담아 다시 요청해 보세요.";

		private void parse는_무관판정을_준다(String region) {
			String regionJson = region == null ? "null" : "\"" + region + "\"";
			server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(
				"{\"region\": " + regionJson + ", \"period\": null, \"interests\": [], \"preferred_order\": [],"
					+ " \"related\": false}",
				MediaType.APPLICATION_JSON));
		}

		private void parse는_관련_해석을_준다(String regionJson, String interestsJson) {
			server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(
				"{\"region\": " + regionJson + ", \"period\": null, \"interests\": " + interestsJson
					+ ", \"preferred_order\": [], \"related\": true}",
				MediaType.APPLICATION_JSON));
		}

		// 검증: FR-ROUTE-19, AC-513-01, AC-513-09, AC-513-10
		@Test
		@DisplayName("무관 문장이면 빈 목록과 전용 안내가 오고 후속 단계를 부르지 않는다 — AI 호출은 parse 1회뿐")
		void 무관_문장이면_빈_목록과_전용_안내가_오고_후속_단계를_부르지_않는다() {
			// 지역 표기가 섞인 무관 문장 — resolve 가 불렸다면 이 매칭으로 MOVE 신호가 실려 null 단언이 깨진다.
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(
				new MentionedRegionMatch("부산광역시", 35.1985, 129.0538,
					35.0512, 128.7602, 35.3891, 129.2723, false)));
			parse는_무관판정을_준다("부산");	// explain 기대는 걸지 않는다 — 나갔다면 verify 가 실패한다

			RouteRecommendResponseDto response =
				service.recommend(USER_ID, 문장요청("부산 사투리로 코드 주석 써 줘"));

			assertThat(response.points()).isEmpty();
			assertThat(response.notice()).isEqualTo(무관_안내);
			assertThat(response.mentionedArea()).isNull();	// 자동 이동 재요청 예외(MSG-487)도 안 열린다
			then(collector).shouldHaveNoInteractions();	// 후보 수집 미도달
			then(regionQueryService).shouldHaveNoInteractions();	// 언급 지역 판정 미도달
			server.verify();	// AI 호출은 parse 1회뿐 — explain 이 나갔다면 여기서 실패한다
		}

		// 검증: FR-ROUTE-19, AC-513-02
		@Test
		void 무관_안내는_후보_없음_안내와_문구가_다르다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			parse는_무관판정을_준다(null);	// 요청 1 — 무관
			parse는_빈해석을_준다();	// 요청 2 — 관련이지만 후보 0건 (문장이 달라 캐시 미스)

			RouteRecommendResponseDto 무관 = service.recommend(USER_ID, 문장요청("롤 정글 동선 짜 줘"));
			clock.advance(Duration.ofSeconds(10));
			RouteRecommendResponseDto 빈후보 = service.recommend(USER_ID, 문장요청("이 근처 축제 보고 싶어"));

			assertThat(무관.notice()).isEqualTo(무관_안내);
			assertThat(빈후보.notice()).isNotNull().isNotEqualTo(무관.notice());
		}

		// 검증: FR-ROUTE-19, AC-513-03
		@Test
		@DisplayName("related 가 true 면 빈 해석이어도 기존 추천이 나온다 — 지역만·관심사만·전 필드 빈 해석 세 경계")
		void related가_true면_빈_해석이어도_기존_추천이_나온다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 35.15, 129.08)));
			parse는_관련_해석을_준다("\"부산\"", "[]");	// 지역만 적은 문장
			explain은_이유를_준다("r1");
			parse는_관련_해석을_준다("null", "[\"맛집\"]");	// 관심사만 적은 문장
			explain은_이유를_준다("r1");
			parse는_관련_해석을_준다("null", "[]");	// 정보 없는 여행 문장 — 빈 해석 (FR-ROUTE-06 유지)
			explain은_이유를_준다("r1");

			for (String 문장 : List.of("부산", "맛집", "이 근처 아무거나")) {
				RouteRecommendResponseDto response = service.recommend(USER_ID, 문장요청(문장));
				assertThat(response.points()).hasSize(1);	// 기존 뷰포트 기준 추천 경로 도달
				assertThat(response.notice()).isNotEqualTo(무관_안내);
				clock.advance(Duration.ofSeconds(10));
			}
			server.verify();
		}

		// 검증: FR-ROUTE-19, AC-513-06
		@Test
		void 무관_응답도_요청_제한_창을_소모한다() {
			parse는_무관판정을_준다(null);
			service.recommend(USER_ID, 문장요청("롤 정글 동선 짜 줘"));
			clock.advance(Duration.ofSeconds(5));	// 10초 창 안

			assertThatThrownBy(() -> service.recommend(USER_ID, 문장요청("롤 정글 동선 짜 줘")))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_RATE_LIMITED);
		}

		// 검증: FR-ROUTE-19, AC-513-07
		@Test
		void 캐시_창_안_재요청은_같은_무관_안내를_재현한다() {
			parse는_무관판정을_준다(null);	// parse 기대는 한 번뿐 — 둘째 요청이 parse 를 사면 verify 가 실패한다

			RouteRecommendResponseDto first = service.recommend(USER_ID, 문장요청("롤 정글 동선 짜 줘"));
			clock.advance(Duration.ofSeconds(10));	// 요청 제한 창은 넘고 캐시 TTL(10분) 안이다
			RouteRecommendResponseDto second = service.recommend(USER_ID, 문장요청("롤 정글 동선 짜 줘"));

			assertThat(second).isEqualTo(first);
			assertThat(second.notice()).isEqualTo(무관_안내);
			server.verify();
		}

		// 검증: FR-ROUTE-19, AC-513-08
		@Test
		void 무관_판정_지표가_unrelated로_남는다() {
			Logger logger = (Logger) LoggerFactory.getLogger(RouteRecommendServiceImpl.class);
			ListAppender<ILoggingEvent> appender = new ListAppender<>();
			appender.start();
			logger.addAppender(appender);
			try {
				parse는_무관판정을_준다(null);

				service.recommend(USER_ID, 문장요청("롤 정글 동선 짜 줘"));

				String line = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
					.filter(message -> message.contains("outcome="))
					.findFirst().orElseThrow();
				assertThat(line).contains("outcome=unrelated").contains("points=0").contains("signal=none");
			} finally {
				logger.detachAppender(appender);
			}
		}
	}

	@Nested
	@DisplayName("관심사 반영 (MSG-514 FR-4) — 실물 수집기·실물 사전 관통")
	class InterestReflection {

		/** 서로 다른 후보 집합에 걸리는 세 미션 — 음식 근거어(국밥), 야경 근거어(불빛), 중립. 거리 단조 배치. */
		private void 세_미션이_있다() {
			given(missionQueryService.getMissionsInViewport(any(), eq(MissionType.EVENT))).willReturn(List.of(
				상시_축제미션(1L, "다리 위 주간", "광안대교 불빛 감상", 35.151, 129.076),
				상시_축제미션(2L, "골목 투어 주간", "돼지국밥 골목 소개", 35.16, 129.085),
				상시_축제미션(3L, "민속 마당", null, 35.17, 129.10)));
		}

		// 검증: FR-ROUTE-18
		@Test
		@DisplayName("같은 화면에서 관심사가 다른 두 문장은 다른 지점 열을 돌려준다 (FR-4 고정 테스트)")
		void 같은_화면에서_관심사가_다른_두_문장은_다른_지점_열을_돌려준다() {
			// 후보가 상한 8 안이라 구성은 같다 — 순서 축(관심사 일치 첫 키)이 없으면 두 응답이 동일해 깨진다.
			세_미션이_있다();
			RouteRecommendService 서비스 = 실수집_서비스();
			parse는_관심사를_준다("맛집");
			explain은_이유를_준다("r1", "r2", "r3");
			parse는_관심사를_준다("야경");
			explain은_이유를_준다("r1", "r2", "r3");

			RouteRecommendResponseDto 맛집 = 서비스.recommend(USER_ID, 문장요청("부산 맛집 코스 짜 줘"));
			clock.advance(Duration.ofSeconds(10));
			RouteRecommendResponseDto 야경 = 서비스.recommend(USER_ID, 문장요청("부산 야경 코스 짜 줘"));

			// "맛집"은 국밥 미션이, "야경"은 불빛 미션이 앞이다 — 원문 어디에도 없는 말이 사전 경유로 이어졌다.
			assertThat(맛집.points().getFirst().missionId()).isEqualTo(2L);
			assertThat(야경.points().getFirst().missionId()).isEqualTo(1L);
			assertThat(맛집.points().stream().map(RoutePointDto::missionId).toList())
				.isNotEqualTo(야경.points().stream().map(RoutePointDto::missionId).toList());
		}

		// 검증: FR-ROUTE-18, FR-ROUTE-06
		@Test
		@DisplayName("관심사가 없는 해석은 종전 거리순 결과를 유지한다 (FR-3 후단, FR-ROUTE-06 유지 확인)")
		void 관심사가_없는_해석은_종전_거리순_결과를_유지한다() {
			세_미션이_있다();
			parse는_빈해석을_준다();
			explain은_이유를_준다("r1", "r2", "r3");

			RouteRecommendResponseDto response = 실수집_서비스().recommend(USER_ID, 문장요청("코스 짜 줘"));

			// 뷰포트 중심(35.15, 129.075)에서 최근접 이웃 — 일치가 하나도 없으면 순수 거리순 그대로다.
			assertThat(response.points()).extracting(RoutePointDto::missionId).containsExactly(1L, 2L, 3L);
		}
	}

	@Nested
	@DisplayName("사실 목록 (MSG-514 결정 3) — 출처, 관심사, 지점 고유 최대 2, 기간")
	class DetailFacts {

		// 검증: FR-ROUTE-05
		@Test
		@DisplayName("코스 지점의 사실 목록에 거리와 시간과 난이도가 실린다 — 스펙 합성 문장 + 소개문")
		void 코스_지점의_사실_목록에_거리와_시간과_난이도가_실린다() {
			PathShape path = new PathShape(
				"{\"type\": \"LineString\", \"coordinates\": [[129.05, 35.16], [129.06, 35.17]]}", List.of());
			given(missionQueryService.getMissionsInViewport(any(), eq(MissionType.COURSE))).willReturn(List.of(
				new MissionResponseDto(5L, MissionType.COURSE.name(), "남파랑길 3코스", null, null, null, path,
					"부산 앞바다를 따라 걷는 길", null, null, null, null, 14000, 330, 2)));
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(content().json("""
					{"text": "바닷가 걷는 코스",
					 "points": [{"name": "남파랑길 3코스", "kind": "mission_course", "facts": [
						"코스 미션 후보", "총 14km, 약 5시간 30분, 난이도 보통", "부산 앞바다를 따라 걷는 길"]}]}
					""", JsonCompareMode.STRICT))
				.andRespond(withSuccess("{\"reasons\": [\"r1\"], \"summary\": \"동선 요약\"}", MediaType.APPLICATION_JSON));

			실수집_서비스().recommend(USER_ID, 문장요청("바닷가 걷는 코스"));

			server.verify();
		}

		// 검증: FR-ROUTE-05
		@Test
		@DisplayName("축제 지점의 사실 목록에 장소와 소개가 실린다 — 장소명·소개문이 기간보다 앞 칸이다")
		void 축제_지점의_사실_목록에_장소와_소개가_실린다() {
			// SteppingClock 기준(2026-06-01) 활성 기간 — naive UTC 를 KST 날짜로 바꿔 표기하는 기존 규칙 유지.
			given(missionQueryService.getMissionsInViewport(any(), eq(MissionType.EVENT))).willReturn(List.of(
				new MissionResponseDto(7L, MissionType.EVENT.name(), "빛 조형물 주간", null,
					LocalDateTime.of(2026, 5, 20, 0, 0), LocalDateTime.of(2026, 6, 10, 0, 0), 박스(35.15, 129.08),
					"밤을 밝히는 조형물 소개", "해운대 해수욕장 특설무대", null, null, null, null, null, null)));
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(content().json("""
					{"text": "주말에 볼만한 것",
					 "points": [{"name": "빛 조형물 주간", "kind": "mission_festival", "facts": [
						"축제 미션 후보", "해운대 해수욕장 특설무대", "밤을 밝히는 조형물 소개",
						"2026-05-20~2026-06-10 진행 중"]}]}
					""", JsonCompareMode.STRICT))
				.andRespond(withSuccess("{\"reasons\": [\"r1\"], \"summary\": \"동선 요약\"}", MediaType.APPLICATION_JSON));

			실수집_서비스().recommend(USER_ID, 문장요청("주말에 볼만한 것"));

			server.verify();
		}

		// 검증: FR-ROUTE-05
		@Test
		@DisplayName("행사 지점의 사실 목록은 종전 구성을 유지한다 — 칩 DTO 에 재료가 없어 출처와 기간뿐이다")
		void 행사_지점의_사실_목록은_종전_구성을_유지한다() {
			given(collector.collect(any(), any())).willReturn(List.of(new RouteCandidate(
				"불꽃 문화제", Kind.EVENT, 35.15, 129.08, GridEncoder.encode(35.15, 129.08), null, 7L,
				LocalDateTime.of(2026, 5, 31, 15, 0), LocalDateTime.of(2026, 6, 10, 14, 59), null, List.of())));
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(content().json("""
					{"text": "부산역 내려서 해운대 축제 보고 싶어",
					 "points": [{"name": "불꽃 문화제", "kind": "event", "facts": [
						"행사 위치", "2026-06-01~2026-06-10 진행 중"]}]}
					""", JsonCompareMode.STRICT))
				.andRespond(withSuccess("{\"reasons\": [\"r1\"], \"summary\": \"동선 요약\"}", MediaType.APPLICATION_JSON));

			service.recommend(USER_ID, 요청());

			server.verify();
		}

		// 검증: FR-ROUTE-05
		@Test
		@DisplayName("사실 목록은 다섯 건과 100자를 넘지 않는다 — AI 계약 상한 (지점당 1~5건, 각 100자)")
		void 사실_목록은_다섯_건과_100자를_넘지_않는다() {
			// 출처 + 관심사 + 고유 2건 + 기간 = 정확히 5. 150자 소개문은 발송 직전 100자 절단을 거친다.
			String 긴소개 = "가".repeat(150);
			given(collector.collect(any(), any())).willReturn(List.of(new RouteCandidate(
				"빛축제", Kind.MISSION_FESTIVAL, 35.15, 129.08, GridEncoder.encode(35.15, 129.08), 12L, null,
				LocalDateTime.of(2026, 5, 31, 15, 0), LocalDateTime.of(2026, 6, 10, 14, 59), "맛집",
				List.of("해운대 특설무대", 긴소개))));
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(jsonPath("$.points[0].facts.length()", is(5)))
				.andExpect(jsonPath("$.points[0].facts[3]", is(긴소개.substring(0, 100))))
				.andRespond(withSuccess("{\"reasons\": [\"r1\"], \"summary\": \"동선 요약\"}", MediaType.APPLICATION_JSON));

			service.recommend(USER_ID, 요청());

			server.verify();
		}

		// 검증: FR-ROUTE-18, FR-ROUTE-05
		@Test
		@DisplayName("이유 요청에 관심사 일치 표기가 실린다 (FR-7) — 출처 바로 다음 칸이다 (결정 3)")
		void 이유_요청에_관심사_일치_표기가_실린다() {
			given(collector.collect(any(), any())).willReturn(List.of(new RouteCandidate(
				"국밥 골목", Kind.PLACE, 35.15, 129.08, GridEncoder.encode(35.15, 129.08), null, null,
				null, null, "맛집", List.of())));
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(jsonPath("$.points[0].facts[1]", is("관심사 '맛집' 일치")))
				.andRespond(withSuccess("{\"reasons\": [\"r1\"], \"summary\": \"동선 요약\"}", MediaType.APPLICATION_JSON));

			service.recommend(USER_ID, 요청());

			server.verify();
		}
	}

	@Nested
	@DisplayName("지표 로그 (MSG-514 §도메인 로직 5)")
	class Metrics {

		// 검증: FR-ROUTE-18
		@Test
		@DisplayName("지표 로그에 관심사 수와 일치 지점 수가 남는다 — 원문은 남기지 않고 수만 남는다")
		void 지표_로그에_관심사_수와_일치_지점_수가_남는다() {
			Logger logger = (Logger) LoggerFactory.getLogger(RouteRecommendServiceImpl.class);
			ListAppender<ILoggingEvent> appender = new ListAppender<>();
			appender.start();
			logger.addAppender(appender);
			try {
				// 관심사 2건 중 비공백 1건, 선별 2지점 중 일치 1지점 — interests=1, matched=1 이 남아야 한다.
				given(collector.collect(any(), any())).willReturn(List.of(
					new RouteCandidate("국밥 골목", Kind.PLACE, 35.15, 129.08, GridEncoder.encode(35.15, 129.08),
						null, null, null, null, "맛집", List.of()),
					장소후보("서점", 35.16, 129.09)));
				server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(
					"{\"region\": null, \"period\": null, \"interests\": [\"맛집\", \" \"], "
						+ "\"preferred_order\": [], \"related\": true}",
					MediaType.APPLICATION_JSON));
				explain은_이유를_준다("r1", "r2");

				service.recommend(USER_ID, 요청());

				String line = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
					.filter(message -> message.contains("outcome="))
					.findFirst().orElseThrow();
				assertThat(line).contains("interests=1").contains("matched=1");
			} finally {
				logger.detachAppender(appender);
			}
		}
	}

	@Nested
	@DisplayName("도보 예산 절단 (MSG-515) — 절단 후 목록만 explain·응답·지표에 실린다")
	class WalkBudgetTrim {

		private static final ViewportDto 서울_뷰포트 = new ViewportDto(37.45, 126.85, 37.65, 127.10);

		/** 세 번째 지점이 보정 누적 13,010m 로 잘리는 배치 — 순서는 카페→서점→먼곳이고 두 지점이 남는다. */
		private List<RouteCandidate> 넓은_배치_후보() {
			return List.of(장소후보("카페", 35.15, 129.08), 장소후보("서점", 35.16, 129.08),
				장소후보("먼곳", 35.24, 129.08));
		}

		// 검증: FR-ROUTE-13
		@Test
		@DisplayName("절단된 지점은 explain 에도 응답에도 실리지 않는다 — AI 입력과 응답 points 가 절단 후 수로 일치")
		void 절단된_지점은_explain에도_응답에도_실리지_않는다() {
			given(collector.collect(any(), any())).willReturn(넓은_배치_후보());
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(jsonPath("$.points.length()", is(2)))
				.andRespond(withSuccess("{\"reasons\": [\"r1\", \"r2\"], \"summary\": \"동선 요약\"}", MediaType.APPLICATION_JSON));

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.points()).extracting(RoutePointDto::name).containsExactly("카페", "서점");
			server.verify();
		}

		// 검증: FR-ROUTE-13
		@Test
		@DisplayName("절단으로 빈 목록이면 explain 없이 빈 응답과 안내가 온다 — 언급 지역 신호도 동봉된다")
		void 절단으로_빈_목록이면_explain_없이_빈_응답과_안내가_온다() {
			// origin(서울 남서귀)→유일 후보(북동귀)가 직선 약 31km — 첫 구간부터 상한 초과라 동선이 빈다.
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 37.65, 127.10)));
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(
				new MentionedRegionMatch("부산광역시", 35.1985, 129.0538,
					35.0512, 128.7602, 35.3891, 129.2723, false)));
			server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(
				"{\"region\": \"부산\", \"period\": null, \"interests\": [], \"preferred_order\": [], \"related\": true}",
				MediaType.APPLICATION_JSON));	// explain 기대는 걸지 않는다 — 나갔다면 verify 가 실패한다

			RouteRecommendResponseDto response = service.recommend(USER_ID, new RouteRecommendRequestDto(
				"부산 축제 보고 싶어", 서울_뷰포트, new OriginDto(37.45, 126.85)));

			assertThat(response.points()).isEmpty();
			assertThat(response.notice())
				.isEqualTo("조건에 맞는 곳을 찾지 못했어요. 문장을 바꾸거나 다른 지역에서 다시 짜 보세요.");
			assertThat(response.mentionedArea()).isNotNull();	// 빈 후보 조기 반환과 같은 형태로 합류한다
			server.verify();
		}

		// 검증: FR-ROUTE-13
		@Test
		@DisplayName("절단으로 2개가 남으면 기존 부족 안내가 붙는다 — FR-ROUTE-07 임계 동작 불변, 축소 전용 안내 없음")
		void 절단으로_2개가_남으면_기존_부족_안내가_붙는다() {
			given(collector.collect(any(), any())).willReturn(넓은_배치_후보());
			parse는_빈해석을_준다();
			explain은_이유를_준다("r1", "r2");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 요청());

			assertThat(response.points()).hasSize(2);
			assertThat(response.notice())
				.isEqualTo("조건에 맞는 곳을 2곳만 찾았어요. 문장을 바꾸거나 다른 지역에서 다시 짜 보세요.");
		}

		// 검증: FR-ROUTE-13
		@Test
		@DisplayName("지표 로그에 trimmed 가 남는다 — 축소 발동 시 matched 는 응답에 남은 일치 지점 수다 (결정 3)")
		void 지표_로그에_trimmed가_남는다() {
			Logger logger = (Logger) LoggerFactory.getLogger(RouteRecommendServiceImpl.class);
			ListAppender<ILoggingEvent> appender = new ListAppender<>();
			appender.start();
			logger.addAppender(appender);
			try {
				RouteCandidate 일치근처 = new RouteCandidate("국밥 골목", Kind.PLACE, 35.15, 129.08,
					GridEncoder.encode(35.15, 129.08), null, null, null, null, "맛집", List.of());
				RouteCandidate 일치먼곳 = new RouteCandidate("먼 국밥집", Kind.PLACE, 35.24, 129.08,
					GridEncoder.encode(35.24, 129.08), null, null, null, null, "맛집", List.of());
				RouteCandidate 미일치 = 장소후보("서점", 35.151, 129.08);
				given(collector.collect(any(), any()))
					.willReturn(List.of(일치근처, 일치먼곳, 미일치))	// 첫 요청 — 일치 먼곳부터 접두 절단
					.willReturn(List.of(일치근처, 미일치));	// 둘째 요청 — 좁은 배치라 무축소
				parse는_관심사를_준다("맛집");	// parse 기대는 한 번 — 둘째 요청은 캐시 히트다
				explain은_이유를_준다("r1");
				explain은_이유를_준다("r1", "r2");

				service.recommend(USER_ID, 요청());
				clock.advance(Duration.ofSeconds(10));
				service.recommend(USER_ID, 요청());

				List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
					.filter(message -> message.contains("outcome=")).toList();
				// 선별 3 - 응답 1 = trimmed 2. 일치 후보는 둘이었지만 matched 는 절단 후 목록의 1이다 (결정 3).
				assertThat(lines.get(0)).contains("points=1").contains("matched=1").contains("trimmed=2");
				assertThat(lines.get(1)).contains("points=2").contains("trimmed=0");
			} finally {
				logger.detachAppender(appender);
			}
		}
	}

	@Nested
	@DisplayName("언급 지역 신호 (MSG-468)")
	class MentionedArea {

		private static final ViewportDto 서울_뷰포트 = new ViewportDto(37.45, 126.85, 37.65, 127.10);

		/** 부산광역시 매칭 그룹 — 이름·좌표가 서버 데이터(regions) 출처라는 정합 검증의 기준값. */
		private MentionedRegionMatch 부산_매칭(boolean overlapsViewport) {
			return new MentionedRegionMatch("부산광역시", 35.1985, 129.0538,
				35.0512, 128.7602, 35.3891, 129.2723, overlapsViewport);
		}

		private void parse는_지역을_준다(String region) {
			server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(
				"{\"region\": \"" + region + "\", \"period\": null, \"interests\": [], \"preferred_order\": [], \"related\": true}",
				MediaType.APPLICATION_JSON));
		}

		private RouteRecommendRequestDto 서울에서_요청(String text) {
			return new RouteRecommendRequestDto(text, 서울_뷰포트, null);
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("화면 밖 지역을 말하면 이동 신호가 실린다 — name 은 서버 정식 표기지 AI 반환 문자열이 아니다")
		void 화면_밖_지역을_말하면_이동_신호가_실린다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 37.55, 126.99)));
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");
			explain은_이유를_준다("r1");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));

			MentionedAreaDto area = response.mentionedArea();
			assertThat(area.name()).isEqualTo("부산광역시");	// AI 가 준 "부산"이 아니다 (데이터 정합)
			assertThat(area.kind()).isEqualTo("MOVE");
			assertThat(area.centerLat()).isEqualTo(35.1985);
			assertThat(area.centerLng()).isEqualTo(129.0538);
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("후보가 없어도 신호는 실린다 — 빈 후보 조기 반환 경로에도 mentionedArea 동봉")
		void 후보가_없어도_신호는_실린다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");	// explain 기대는 걸지 않는다 — 빈 후보는 explain 을 부르지 않는다

			RouteRecommendResponseDto response = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));

			assertThat(response.points()).isEmpty();
			assertThat(response.notice()).isNotNull();
			assertThat(response.mentionedArea()).isNotNull();	// 후보 0 응답이야말로 이동 제안이 가장 필요한 지점
			server.verify();
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("신호가 있어도 추천 결과는 그대로다 — points·notice 는 신호 유무와 무관하게 동일")
		void 신호가_있어도_추천_결과는_그대로다() {
			given(collector.collect(any(), any()))
				.willReturn(List.of(장소후보("카페", 37.55, 126.99), 장소후보("서점", 37.56, 127.00)));
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_빈해석을_준다();	// 첫 요청 — 지역 무언급
			explain은_이유를_준다("r1", "r2");
			parse는_지역을_준다("부산");	// 둘째 요청 — 문장이 달라 캐시 미스
			explain은_이유를_준다("r1", "r2");

			RouteRecommendResponseDto 무신호 = service.recommend(USER_ID, 서울에서_요청("축제 보고 싶어"));
			clock.advance(Duration.ofSeconds(10));
			RouteRecommendResponseDto 신호 = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));

			assertThat(무신호.mentionedArea()).isNull();
			assertThat(신호.mentionedArea()).isNotNull();
			assertThat(신호.points()).isEqualTo(무신호.points());
			assertThat(신호.notice()).isEqualTo(무신호.notice());
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("모르는 지역 이름은 신호 없이 정상 추천된다 — 대조 실패는 무신호일 뿐 실패가 아니다")
		void 모르는_지역_이름은_신호_없이_정상_추천된다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 37.55, 126.99)));
			// zones·regions 는 기본 스텁(빈 목록) — 오타·해외 지명·미등재 통칭 전부 이 경로다 (FR-3)
			parse는_지역을_준다("샌프란시스코");
			explain은_이유를_준다("r1");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 서울에서_요청("샌프란시스코 가고 싶어"));

			assertThat(response.points()).hasSize(1);
			assertThat(response.mentionedArea()).isNull();
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("판정 조회가 실패해도 추천은 성공한다 — 신호는 부가 정보라 실패를 전파하지 않는다")
		void 판정_조회가_실패해도_추천은_성공한다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 37.55, 126.99)));
			given(regionQueryService.matchMentionedRegions(any(), any()))
				.willThrow(new IllegalStateException("region 조회 실패"));
			parse는_지역을_준다("부산");
			explain은_이유를_준다("r1");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));

			assertThat(response.points()).hasSize(1);	// 추천은 그대로 성공
			assertThat(response.mentionedArea()).isNull();	// 예외는 무신호로 삼켜진다
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("캐시 창 안 재요청은 같은 신호를 받는다 — 판정은 캐시된 해석 위에서 재현된다")
		void 캐시_창_안_재요청은_같은_신호를_받는다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 37.55, 126.99)));
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");	// parse 기대는 한 번뿐 — 둘째 요청이 parse 를 사면 verify 가 실패한다
			explain은_이유를_준다("r1");
			explain은_이유를_준다("r1");

			RouteRecommendResponseDto first = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));
			clock.advance(Duration.ofSeconds(10));	// 요청 제한 창은 넘고 캐시 TTL(10분) 안이다
			RouteRecommendResponseDto second = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));

			assertThat(first.mentionedArea()).isNotNull();
			assertThat(second.mentionedArea()).isEqualTo(first.mentionedArea());
			// 판정은 캐시 히트에도 재실행된다 — 저장된 신호 재사용이 아니라 같은 region 문자열의 재현이다.
			then(regionQueryService).should(times(2)).matchMentionedRegions(any(), any());
			server.verify();
		}
	}

	@Nested
	@DisplayName("요청 제한 예외 (MSG-487 FR-9) — 자동 이동 직후 재요청 1회")
	class RateLimitExemption {

		private static final ViewportDto 서울_뷰포트 = new ViewportDto(37.45, 126.85, 37.65, 127.10);

		/** overlapsViewport=false → MOVE, true → 서울 뷰포트와 실겹침 0 이라 ZOOM_OUT (MentionedArea 와 동일 픽스처). */
		private MentionedRegionMatch 부산_매칭(boolean overlapsViewport) {
			return new MentionedRegionMatch("부산광역시", 35.1985, 129.0538,
				35.0512, 128.7602, 35.3891, 129.2723, overlapsViewport);
		}

		private void parse는_지역을_준다(String region) {
			server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(
				"{\"region\": \"" + region + "\", \"period\": null, \"interests\": [], \"preferred_order\": [], \"related\": true}",
				MediaType.APPLICATION_JSON));
		}

		private RouteRecommendRequestDto 서울에서_요청(String text) {
			return new RouteRecommendRequestDto(text, 서울_뷰포트, null);
		}

		// 검증: FR-ROUTE-12
		@Test
		void 이동_신호_응답_직후의_재요청_한_번은_제한을_통과한다() {
			given(collector.collect(any(), any())).willReturn(List.of(장소후보("카페", 37.55, 126.99)));
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");
			explain은_이유를_준다("r1");
			parse는_빈해석을_준다();	// 재요청 — 지도가 옮겨져 뷰포트가 달라지므로 캐시 미스
			explain은_이유를_준다("r1");

			RouteRecommendResponseDto first = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));
			assertThat(first.mentionedArea().kind()).isEqualTo("MOVE");

			clock.advance(Duration.ofSeconds(5));	// 10초 창 안 — 예외 없이는 14429 인 시점
			assertThatCode(() -> service.recommend(USER_ID, 요청())).doesNotThrowAnyException();
		}

		// 검증: FR-ROUTE-12
		@Test
		@DisplayName("예외로 통과한 재요청 다음 요청은 다시 제한된다 — 예외 통과도 새 10초 창을 연다")
		void 예외로_통과한_재요청_다음_요청은_다시_제한된다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");
			parse는_빈해석을_준다();

			service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));	// MOVE — 예외 부여
			clock.advance(Duration.ofSeconds(5));
			service.recommend(USER_ID, 요청());	// 예외 소비 통과 (t=5)

			clock.advance(Duration.ofSeconds(5));	// t=10 — 예외 통과 시각으로부터 5초
			assertThatThrownBy(() -> service.recommend(USER_ID, 요청()))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_RATE_LIMITED);
		}

		// 검증: FR-ROUTE-12
		@Test
		void 이동_신호_없는_응답_뒤_재요청은_그대로_제한된다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			parse는_빈해석을_준다();

			service.recommend(USER_ID, 서울에서_요청("축제 보고 싶어"));	// 무신호
			clock.advance(Duration.ofSeconds(5));

			assertThatThrownBy(() -> service.recommend(USER_ID, 서울에서_요청("축제 보고 싶어")))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_RATE_LIMITED);
		}

		// 검증: FR-ROUTE-12
		@Test
		void 축소_신호는_재요청_예외를_만들지_않는다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(true)));
			parse는_지역을_준다("부산");

			RouteRecommendResponseDto response = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));
			assertThat(response.mentionedArea().kind()).isEqualTo("ZOOM_OUT");
			clock.advance(Duration.ofSeconds(5));

			assertThatThrownBy(() -> service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어")))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_RATE_LIMITED);
		}

		// 검증: FR-ROUTE-12
		@Test
		@DisplayName("예외 통과 요청의 응답에 또 이동 신호가 실려도 연속 통과되지 않는다 — 연쇄 차단 가드")
		void 예외_통과_요청의_응답에_또_이동_신호가_실려도_연속_통과되지_않는다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");	// 요청 1 — MOVE
			parse는_지역을_준다("부산");	// 요청 2 — 캐시 미스, 응답에 또 MOVE

			service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));
			clock.advance(Duration.ofSeconds(5));
			RouteRecommendResponseDto second = service.recommend(USER_ID, 요청());	// 예외 소비 통과
			assertThat(second.mentionedArea().kind()).isEqualTo("MOVE");	// MOVE 가 또 실렸지만 재부여는 없다

			clock.advance(Duration.ofSeconds(1));
			assertThatThrownBy(() -> service.recommend(USER_ID, 요청()))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_RATE_LIMITED);
		}

		// 검증: FR-ROUTE-12
		@Test
		@DisplayName("이동 신호 뒤 창 밖 정상 요청이 지나가면 예외는 소거된다 — 통과 전이가 남은 예외를 지운다")
		void 이동_신호_뒤_창_밖_정상_요청이_지나가면_예외는_소거된다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");
			parse는_빈해석을_준다();

			service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));	// MOVE — 예외 부여
			clock.advance(Duration.ofSeconds(10));
			service.recommend(USER_ID, 서울에서_요청("축제 보고 싶어"));	// 창 밖 정상 통과 — 예외 소거

			clock.advance(Duration.ofSeconds(1));
			assertThatThrownBy(() -> service.recommend(USER_ID, 서울에서_요청("축제 보고 싶어")))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_RATE_LIMITED);
		}

		// 검증: FR-ROUTE-12
		@Test
		@DisplayName("처리가 늦어진 이동 신호 응답은 다른 창에 예외를 달지 않는다 — 조건부 부여 (시도 시각 바인딩)")
		void 처리가_늦어진_이동_신호_응답은_다른_창에_예외를_달지_않는다() {
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");	// 요청 1
			parse는_빈해석을_준다();	// 요청 2 (요청 1 처리 중 삽입)
			// 요청 1의 후보 수집 시점에 10초를 흘리고 요청 2를 끼워 넣는다 — 외부 호출 지연으로 처리가 창을
			// 넘긴 사이 다른 요청이 새 창을 정상 선점하는 시나리오의 동기적 재현이다.
			AtomicBoolean 삽입됨 = new AtomicBoolean(false);
			given(collector.collect(any(), any())).willAnswer(invocation -> {
				if (삽입됨.compareAndSet(false, true)) {
					clock.advance(Duration.ofSeconds(10));
					service.recommend(USER_ID, 서울에서_요청("축제 보고 싶어"));	// 요청 2 — 새 창 정상 선점
				}
				return List.of();
			});

			RouteRecommendResponseDto delayed = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));
			assertThat(delayed.mentionedArea().kind()).isEqualTo("MOVE");	// 뒤늦게 완성된 MOVE 응답

			clock.advance(Duration.ofSeconds(1));	// 요청 2가 선점한 창 안 — 예외가 달렸다면 통과해 버린다
			assertThatThrownBy(() -> service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어")))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_RATE_LIMITED);
		}

		// 검증: FR-ROUTE-12
		@Test
		@DisplayName("후보가 없는 이동 신호 응답도 예외를 부여한다 — 빈 후보 조기 반환 경로 합류 뒤 부여")
		void 후보가_없는_이동_신호_응답도_예외를_부여한다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");
			parse는_빈해석을_준다();

			RouteRecommendResponseDto first = service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));
			assertThat(first.points()).isEmpty();
			assertThat(first.mentionedArea()).isNotNull();

			clock.advance(Duration.ofSeconds(5));
			assertThatCode(() -> service.recommend(USER_ID, 요청())).doesNotThrowAnyException();
		}

		// 검증: FR-ROUTE-12
		@Test
		@DisplayName("뷰포트 검증 거부는 예외를 소모하지 않는다 — 창을 안 쓰는 거부는 예외도 안 쓴다")
		void 뷰포트_검증_거부는_예외를_소모하지_않는다() {
			given(collector.collect(any(), any())).willReturn(List.of());
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(부산_매칭(false)));
			parse는_지역을_준다("부산");
			parse는_빈해석을_준다();

			service.recommend(USER_ID, 서울에서_요청("부산 축제 보고 싶어"));	// MOVE — 예외 부여
			clock.advance(Duration.ofSeconds(5));
			assertThatThrownBy(() -> service.recommend(USER_ID,
				new RouteRecommendRequestDto("해운대 가자", new ViewportDto(35.1, 128.95, 35.1, 129.20), null)))
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.INVALID_VIEWPORT);	// 넓이 0 — 청구 전 거부

			assertThatCode(() -> service.recommend(USER_ID, 요청())).doesNotThrowAnyException();	// 예외 잔존
		}
	}

	@Nested
	@DisplayName("입력 경계 (모듈 1)")
	class InputBoundary {

		// 검증: FR-ROUTE-01, NFR-SEC-08
		@Test
		@DisplayName("잘못된 좌표는 AI 호출 전에 걸러진다 — 전 케이스에서 parse 미호출 (기대 0회)")
		void 잘못된_좌표는_AI_호출_전에_걸러진다() {
			뷰포트가_거부된다(new ViewportDto(35.1, 128.95, 35.1, 129.20), RouteErrorCode.INVALID_VIEWPORT); // 넓이 0
			뷰포트가_거부된다(new ViewportDto(35.05, 128.95, 91.0, 129.20), RouteErrorCode.INVALID_VIEWPORT); // 위도 91
			뷰포트가_거부된다(new ViewportDto(Double.NaN, 128.95, 35.25, 129.20), RouteErrorCode.INVALID_VIEWPORT);
			뷰포트가_거부된다(new ViewportDto(35.25, 128.95, 35.05, 129.20), RouteErrorCode.INVALID_VIEWPORT); // 뒤집힘
			뷰포트가_거부된다(new ViewportDto(35.05, 128.95, 35.25, 129.50), RouteErrorCode.VIEWPORT_TOO_LARGE);

			server.verify(); // 기대를 하나도 걸지 않았다 — 요청이 한 번이라도 나갔다면 그 시점에 이미 실패했다
		}

		private void 뷰포트가_거부된다(ViewportDto viewport, RouteErrorCode expected) {
			RouteRecommendRequestDto request = new RouteRecommendRequestDto("해운대 가자", viewport, null);
			assertThatThrownBy(() -> service.recommend(USER_ID, request))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", expected);
		}
	}

	/* ---------- 지원 타입 ---------- */

	/** 격자 하나를 덮는 구역 — 실물 리졸버로 표시명 재료 조립을 검증한다 (단일 셀이라 위치 코드는 A-1). */
	private Zone 구역(String gridId) {
		GridIndex index = GridEncoder.decode(gridId);
		return Zone.builder()
			.zoneKey("route-test")
			.name("해운대")
			.minGridY((int) index.gridY())
			.maxGridY((int) index.gridY())
			.minGridX((int) index.gridX())
			.maxGridX((int) index.gridX())
			.priority(0)
			.build();
	}

	/** 전진 가능한 고정 시계 — 요청 제한 창(10초)·캐시 TTL(10분)을 실제 대기 없이 넘기 위한 것. */
	private static final class SteppingClock extends Clock {

		private Instant instant = Instant.parse("2026-06-01T00:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}
	}
}
