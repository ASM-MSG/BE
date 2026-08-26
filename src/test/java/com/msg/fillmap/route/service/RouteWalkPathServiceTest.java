package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.route.config.RouteWalkProperties;
import com.msg.fillmap.route.dto.RouteWalkPathRequestDto;
import com.msg.fillmap.route.dto.RouteWalkPathRequestDto.SegmentDto;
import com.msg.fillmap.route.dto.RouteWalkPathResponseDto;
import com.msg.fillmap.route.dto.RouteWalkPathResponseDto.PathPointDto;
import com.msg.fillmap.route.dto.RouteWalkPathResponseDto.WalkSegmentDto;
import com.msg.fillmap.route.exception.RouteErrorCode;
import com.msg.fillmap.route.service.TmapWalkClient.Coordinate;
import com.msg.fillmap.route.service.TmapWalkClient.WalkPath;

/**
 * 보행 경로 플로우 통합 검증 (MSG-483 §도메인 로직). TMap 은 MockRestServiceServer 스텁, 캐시·한도
 * 카운터는 실제 Redis(localhost:6379) 실물이다 (RouteRecommendServiceTest 구도 + M3 컴포넌트 실물).
 * 한도 카운터는 고정 과거 Clock(1999-06-15)으로 날짜 키를 실서비스·타 테스트와 격리한다.
 * TMap 을 부르면 안 되는 시나리오는 기대 없는 서버가 잡는다 — 예기치 않은 요청은 실패 세그먼트가 되어
 * resolved:true 단언이 깨진다.
 */
@DisplayName("RouteWalkPathService — 보행 경로 플로우 통합 (TMap 스텁 + 실제 Redis)")
class RouteWalkPathServiceTest {

	private static final String BASE_URL = "https://tmap.test";
	private static final String PEDESTRIAN_URL = BASE_URL + "/tmap/routes/pedestrian?version=1";
	/** 1999-06-15T00:00:00Z = KST 1999-06-15 09:00 — 한도 키 route:walk:daily:19990615. */
	private static final Instant FIXED_INSTANT = Instant.parse("1999-06-15T00:00:00Z");
	private static final String DAILY_KEY = "route:walk:daily:19990615";

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static RouteWalkSegmentCache cache;

	private MockRestServiceServer server;
	private RouteWalkPathService service;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		cache = new RouteWalkSegmentCache(redisTemplate, new ObjectMapper());
	}

	@BeforeEach
	void setUp() {
		service = buildService(cache, limiter(900));
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(DAILY_KEY);
		for (int i = 0; i < 9; i++) {
			redisTemplate.delete(segKey(seg(i)));
		}
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	/* ---------- 픽스처 ---------- */

	private static RouteWalkProperties properties(int dailyLimit) {
		return new RouteWalkProperties(true, BASE_URL, "test-app-key", dailyLimit, Duration.ofSeconds(3));
	}

	private static RouteWalkDailyLimiter limiter(int dailyLimit) {
		return new RouteWalkDailyLimiter(redisTemplate, properties(dailyLimit),
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	private RouteWalkPathService buildService(RouteWalkSegmentCache segmentCache, RouteWalkDailyLimiter dailyLimiter) {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		TmapWalkClient walkClient = new TmapWalkClient(builder, properties(900));
		return new RouteWalkPathServiceImpl(provider(walkClient), segmentCache, dailyLimiter);
	}

	private static ObjectProvider<TmapWalkClient> provider(TmapWalkClient walkClient) {
		@SuppressWarnings("unchecked")
		ObjectProvider<TmapWalkClient> provider = mock(ObjectProvider.class);
		given(provider.getIfAvailable()).willReturn(walkClient);
		return provider;
	}

	/** i번째 테스트 세그먼트 — 한국 서비스 범위 안, i 마다 다른 키. */
	private static SegmentDto seg(int i) {
		return new SegmentDto(35.1 + i * 0.001, 129.1, 35.2, 129.2);
	}

	/** 캐시 키 재구성 — Double.toString 정규형 (RouteWalkSegmentCache 키 포맷 미러). */
	private static String segKey(SegmentDto segment) {
		return "route:walk:seg:" + segment.startLat() + ":" + segment.startLng()
			+ ":" + segment.endLat() + ":" + segment.endLng();
	}

	private static RouteWalkPathRequestDto 요청(SegmentDto... segments) {
		return new RouteWalkPathRequestDto(List.of(segments));
	}

	private static String tmap응답(int distance) {
		return "{\"type\":\"FeatureCollection\",\"features\":["
			+ "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[129.1,35.1]},"
			+ "\"properties\":{\"pointType\":\"SP\",\"totalDistance\":" + distance + "}},"
			+ "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\","
			+ "\"coordinates\":[[129.1,35.1],[129.2,35.2]]},\"properties\":{\"distance\":" + distance + "}}]}";
	}

	private static WalkSegmentDto 성공_세그먼트(int distance) {
		return new WalkSegmentDto(true, List.of(new PathPointDto(35.1, 129.1), new PathPointDto(35.2, 129.2)),
			distance);
	}

	private static final WalkSegmentDto 실패_세그먼트 = new WalkSegmentDto(false, null, null);

	private void tmap이_응답한다(int distance) {
		server.expect(requestTo(PEDESTRIAN_URL))
			.andRespond(withSuccess(tmap응답(distance), MediaType.APPLICATION_JSON));
	}

	@Nested
	@DisplayName("성공 흐름")
	class Success {

		// 검증: FR-ROUTE-16
		@Test
		void 보행_경로_조회는_세그먼트별_좌표열과_실거리를_요청_순서대로_돌려준다() {
			// startY(=startLat) 대조로 호출 순서까지 고정한다 — 응답 순서는 요청 순서와 같아야 한다.
			server.expect(requestTo(PEDESTRIAN_URL))
				.andExpect(jsonPath("$.startY", is(seg(0).startLat())))
				.andRespond(withSuccess(tmap응답(100), MediaType.APPLICATION_JSON));
			server.expect(requestTo(PEDESTRIAN_URL))
				.andExpect(jsonPath("$.startY", is(seg(1).startLat())))
				.andRespond(withSuccess(tmap응답(200), MediaType.APPLICATION_JSON));

			RouteWalkPathResponseDto response = service.walkPaths(요청(seg(0), seg(1)));

			assertThat(response.segments()).containsExactly(성공_세그먼트(100), 성공_세그먼트(200));
			server.verify();
		}

		// 검증: FR-ROUTE-16
		@Test
		void 출발지_구간을_포함한_여덟_세그먼트를_한_요청으로_처리한다() {
			for (int i = 0; i < 8; i++) {
				tmap이_응답한다(100 + i);
			}

			RouteWalkPathResponseDto response = service.walkPaths(
				요청(IntStream.range(0, 8).mapToObj(RouteWalkPathServiceTest::seg).toArray(SegmentDto[]::new)));

			assertThat(response.segments()).hasSize(8);
			assertThat(response.segments()).allMatch(WalkSegmentDto::resolved);
			server.verify();
		}

		// 검증: FR-ROUTE-16, NFR-OPS-09
		@Test
		void 같은_좌표쌍_재조회는_외부_호출_없이_캐시로_응답한다() {
			tmap이_응답한다(742);
			RouteWalkPathResponseDto first = service.walkPaths(요청(seg(0)));

			// 재조회 — 서버 기대가 소진된 상태라 외부 호출이 나가면 그 세그먼트는 실패가 된다.
			RouteWalkPathResponseDto second = service.walkPaths(요청(seg(0)));

			assertThat(first.segments()).containsExactly(성공_세그먼트(742));
			assertThat(second.segments()).containsExactly(성공_세그먼트(742));
			server.verify();
		}

		// 검증: NFR-OPS-09, FR-ROUTE-16
		@Test
		void 캐시_조회가_실패하면_미스로_취급해_TMap_호출이_진행된다() {
			LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
			deadFactory.afterPropertiesSet();
			StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
			deadTemplate.afterPropertiesSet();
			RouteWalkSegmentCache deadCache = new RouteWalkSegmentCache(deadTemplate, new ObjectMapper());
			RouteWalkPathService deadCacheService = buildService(deadCache, limiter(900));
			tmap이_응답한다(742);

			RouteWalkPathResponseDto response = deadCacheService.walkPaths(요청(seg(0)));

			assertThat(response.segments()).containsExactly(성공_세그먼트(742));
			server.verify();
			deadFactory.destroy();
		}
	}

	@Nested
	@DisplayName("부분 실패 · 요청 내 단락")
	class PartialFailure {

		// 검증: FR-ROUTE-17
		@Test
		void 실패한_세그먼트만_실패로_표시되고_나머지는_경로가_실린다() {
			// 상태 코드가 있는 실패(5xx)는 그 세그먼트만 실패하고 다음 세그먼트를 계속 진행한다.
			server.expect(requestTo(PEDESTRIAN_URL)).andRespond(withServerError());
			tmap이_응답한다(200);

			RouteWalkPathResponseDto response = service.walkPaths(요청(seg(0), seg(1)));

			assertThat(response.segments()).containsExactly(실패_세그먼트, 성공_세그먼트(200));
			server.verify();
		}

		// 검증: FR-ROUTE-17
		@Test
		void 연결_실패나_타임아웃이_나면_남은_미스_세그먼트는_호출_없이_실패_처리한다() {
			// 기대는 한 건뿐 — 남은 두 세그먼트가 호출을 내면 server.verify 이전에 실패 세그먼트로 드러난다.
			server.expect(requestTo(PEDESTRIAN_URL))
				.andRespond(withException(new SocketTimeoutException("read timed out")));

			// TmapUnreachableException 은 서비스가 삼킨다 — 전파되면 500 경로다 (200 + 전 세그먼트 실패여야 한다).
			RouteWalkPathResponseDto response = service.walkPaths(요청(seg(0), seg(1), seg(2)));

			assertThat(response.segments()).containsExactly(실패_세그먼트, 실패_세그먼트, 실패_세그먼트);
			server.verify();
		}
	}

	@Nested
	@DisplayName("일 한도 방어")
	class DailyLimit {

		// 검증: FR-ROUTE-17, NFR-OPS-09
		@Test
		void 일_한도에_닿으면_외부_호출_없이_미스_세그먼트가_전부_실패로_떨어진다() {
			RouteWalkPathService exhaustedService = buildService(cache, limiter(0));

			RouteWalkPathResponseDto response = exhaustedService.walkPaths(요청(seg(0), seg(1), seg(2)));

			assertThat(response.segments()).containsExactly(실패_세그먼트, 실패_세그먼트, 실패_세그먼트);
			// "카운터 증가도 없이"(스펙 196행)의 유일한 관측 증거 — 미스 3건인데 첫 거부의 선점 1건만 계수됐다.
			// 남은 미스마다 tryAcquire 를 재호출하면 이 값이 3 이 되어 카운터가 한도 너머로 부푼다.
			assertThat(redisTemplate.opsForValue().get(DAILY_KEY)).isEqualTo("1");
			server.verify();
		}

		// 검증: FR-ROUTE-17, NFR-OPS-09
		@Test
		void 한도_소진_중에도_캐시에_있는_세그먼트는_경로를_돌려준다() {
			cache.put(seg(1).startLat(), seg(1).startLng(), seg(1).endLat(), seg(1).endLng(),
				new WalkPath(List.of(new Coordinate(35.1, 129.1), new Coordinate(35.2, 129.2)), 742));
			RouteWalkPathService exhaustedService = buildService(cache, limiter(0));

			RouteWalkPathResponseDto response = exhaustedService.walkPaths(요청(seg(0), seg(1)));

			// 캐시 히트는 외부 호출이 아니다 — 한도 소진 중에도 성공으로 준다.
			assertThat(response.segments()).containsExactly(실패_세그먼트, 성공_세그먼트(742));
			server.verify();
		}

		// 검증: NFR-OPS-09, FR-ROUTE-17
		@Test
		void 한도_카운터의_Redis_오류_중에는_미스_세그먼트가_호출_없이_실패로_떨어진다() {
			LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
			deadFactory.afterPropertiesSet();
			StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
			deadTemplate.afterPropertiesSet();
			RouteWalkDailyLimiter deadLimiter = new RouteWalkDailyLimiter(deadTemplate, properties(900),
				Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
			RouteWalkPathService deadLimiterService = buildService(cache, deadLimiter);

			RouteWalkPathResponseDto response = deadLimiterService.walkPaths(요청(seg(0)));

			// 계수 불능이면 호출을 막는다 — 캐시 오류(미스 취급·호출 진행)와 반대 방향의 보수 폴백.
			assertThat(response.segments()).containsExactly(실패_세그먼트);
			server.verify();
			deadFactory.destroy();
		}
	}

	@Nested
	@DisplayName("검증 · 게이트")
	class Validation {

		private void 거부된다(RouteWalkPathRequestDto request) {
			assertThatThrownBy(() -> service.walkPaths(request))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.INVALID_WALK_SEGMENTS);
		}

		@Test
		void 세그먼트가_없거나_아홉_개_이상이면_14402로_거부한다() {
			거부된다(new RouteWalkPathRequestDto(null));
			거부된다(new RouteWalkPathRequestDto(List.of()));
			거부된다(요청(IntStream.range(0, 9).mapToObj(RouteWalkPathServiceTest::seg).toArray(SegmentDto[]::new)));
		}

		@Test
		void 세그먼트_원소가_null이면_14402로_거부한다() {
			// JSON [null] 이 목록 크기 검증만 통과해 null 역참조 500 으로 새는 것을 막는다 (Codex 4라운드 적발).
			거부된다(new RouteWalkPathRequestDto(Arrays.asList(seg(0), null)));
		}

		// 검증: SRS 2.4 제약의 시행
		@Test
		void 좌표가_한국_서비스_범위_밖이면_14402로_거부한다() {
			거부된다(요청(new SegmentDto(32.9, 129.1, 35.2, 129.2)));      // 위도 남단 밖
			거부된다(요청(new SegmentDto(35.1, 129.1, 35.2, 132.1)));      // 경도 동단 밖
			거부된다(요청(new SegmentDto(Double.NaN, 129.1, 35.2, 129.2)));  // NaN — 범위 비교가 함께 거른다
			거부된다(요청(new SegmentDto(null, 129.1, 35.2, 129.2)));      // 좌표 누락(null) — 언박싱 NPE 가드
		}

		@Test
		void 플래그가_꺼진_환경에서는_14504_비활성_응답이_나간다() {
			@SuppressWarnings("unchecked")
			ObjectProvider<TmapWalkClient> emptyProvider = mock(ObjectProvider.class);
			given(emptyProvider.getIfAvailable()).willReturn(null);
			RouteWalkPathService disabledService = new RouteWalkPathServiceImpl(emptyProvider, cache, limiter(900));

			assertThatThrownBy(() -> disabledService.walkPaths(요청(seg(0))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_WALK_DISABLED);

			// 처리 순서는 검증(14402)이 플래그 게이트(14504)보다 먼저다 (§도메인 로직 도입부).
			assertThatThrownBy(() -> disabledService.walkPaths(new RouteWalkPathRequestDto(List.of())))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.INVALID_WALK_SEGMENTS);
		}
	}
}
