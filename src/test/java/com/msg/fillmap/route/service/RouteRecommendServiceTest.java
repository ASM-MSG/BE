package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionQueryService.MentionedRegionMatch;
import com.msg.fillmap.route.config.RouteAiProperties;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto.ViewportDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto.MentionedAreaDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto.RoutePointDto;
import com.msg.fillmap.route.exception.RouteErrorCode;
import com.msg.fillmap.route.service.RouteCandidate.Kind;
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
		"{\"region\": null, \"period\": null, \"interests\": [], \"preferred_order\": []}";

	private final RouteCandidateCollector collector = mock(RouteCandidateCollector.class);
	private final ZoneNameQueryService zoneNameQueryService = mock(ZoneNameQueryService.class);
	private final GridQueryService gridQueryService = mock(GridQueryService.class);
	// 언급 지역 신호(MSG-468)의 데이터 출처 두 계약은 mock, 판정 합성(리졸버)은 실물이다 — 기본 스텁(빈 목록)은
	// 대조 실패라 기존 시나리오는 전부 무신호로 흐른다.
	private final ZoneQueryService zoneQueryService = mock(ZoneQueryService.class);
	private final RegionQueryService regionQueryService = mock(RegionQueryService.class);
	private final SteppingClock clock = new SteppingClock();

	private MockRestServiceServer server;
	private RouteRecommendService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		RouteIntentClient intentClient = new RouteIntentClient(builder,
			new RouteAiProperties(true, BASE_URL, Duration.ofSeconds(10)));

		@SuppressWarnings("unchecked")
		ObjectProvider<RouteIntentClient> provider = mock(ObjectProvider.class);
		given(provider.getIfAvailable()).willReturn(intentClient);
		given(zoneNameQueryService.resolver()).willReturn(new ZoneNameResolver(List.of()));
		service = new RouteRecommendServiceImpl(provider, collector, zoneNameQueryService, gridQueryService,
			new RouteMentionedAreaResolver(zoneQueryService, regionQueryService), clock);
	}

	/* ---------- 픽스처 ---------- */

	private RouteRecommendRequestDto 요청() {
		return new RouteRecommendRequestDto("부산역 내려서 해운대 축제 보고 싶어", 뷰포트, null);
	}

	private RouteCandidate 장소후보(String name, double lat, double lng) {
		return new RouteCandidate(name, Kind.PLACE, lat, lng, GridEncoder.encode(lat, lng),
			null, null, null, null, null);
	}

	private void parse는_빈해석을_준다() {
		server.expect(requestTo(PARSE_URL)).andRespond(withSuccess(빈_해석_응답, MediaType.APPLICATION_JSON));
	}

	private void explain은_이유를_준다(String... reasons) {
		String body = "{\"reasons\": [" + String.join(", ",
			List.of(reasons).stream().map(reason -> "\"" + reason + "\"").toList()) + "]}";
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
				LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0), "축제");
			RouteCandidate 행사 = new RouteCandidate("불꽃축제", Kind.EVENT, 35.16, 129.09,
				GridEncoder.encode(35.16, 129.09), null, 7L, null, null, null);
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

		// 검증: FR-ROUTE-05
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
		@DisplayName("facts 는 지점마다 1건 이상이다 — 출처 문장 상시 1건 + 직전 거리, 사용자 식별 정보 없음")
		void facts는_지점마다_1건_이상이다() {
			RouteCandidate 카페 = 장소후보("카페", 35.15, 129.08);
			RouteCandidate 서점 = 장소후보("서점", 35.16, 129.09);
			given(collector.collect(any(), any())).willReturn(List.of(카페, 서점));
			double km = RouteOrderPlanner.distanceMeters(카페.lat(), 카페.lng(), 서점.lat(), 서점.lng()) / 1000.0;
			parse는_빈해석을_준다();
			// strict 비교 — 기간·관심사가 없는 후보도 출처 문장 1건이 보장되고(하한 1), 여분 필드가 없다.
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(content().json("""
					{"points": [
						{"name": "카페", "kind": "place", "facts": ["장소 검색 결과"]},
						{"name": "서점", "kind": "place", "facts": ["장소 검색 결과", "%s"]}]}
					""".formatted(String.format(Locale.ROOT, "이전 지점에서 %.1fkm", km)), JsonCompareMode.STRICT))
				.andRespond(withSuccess("{\"reasons\": [\"r1\", \"r2\"]}", MediaType.APPLICATION_JSON));

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
				LocalDateTime.of(2026, 7, 31, 15, 0), LocalDateTime.of(2026, 8, 31, 14, 59), null)));
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(jsonPath("$.points[0].facts[1]", is("2026-08-01~2026-08-31 진행 중")))
				.andRespond(withSuccess("{\"reasons\": [\"r1\"]}", MediaType.APPLICATION_JSON));

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
				12L, null, null, null, null)));
			parse는_빈해석을_준다();
			server.expect(requestTo(EXPLAIN_URL))
				.andExpect(jsonPath("$.points[0].name", is(제목.substring(0, 100))))
				.andRespond(withSuccess("{\"reasons\": [\"r1\"]}", MediaType.APPLICATION_JSON));

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
				"{\"region\": \"" + region + "\", \"period\": null, \"interests\": [], \"preferred_order\": []}",
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
