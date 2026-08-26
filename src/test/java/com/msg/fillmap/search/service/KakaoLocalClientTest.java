package com.msg.fillmap.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.search.exception.SearchErrorCode;
import com.msg.fillmap.search.service.KakaoLocalClient.KakaoPlace;

/**
 * 카카오 로컬 어댑터를 MockRestServiceServer(spring-test, 신규 의존성 없음)로 스텁해 계약 매핑만 검증한다
 * (MSG-251 §D7 — AiClientTest 선례 구도). 실 카카오 검증은 DoD 수동(q=부산대 1회)과 §D6 벤치가 겸한다.
 * builder 에 bind 후 SearchConfig 와 같은 baseUrl·KakaoAK 헤더를 얹어 빌드한 RestClient 로 클라이언트를 만든다.
 */
@DisplayName("KakaoLocalClient — 카카오 로컬 keyword.json 계약 매핑")
class KakaoLocalClientTest {

	private static final String BASE_URL = "https://kakao.test";
	private static final String REST_API_KEY = "test-rest-key";
	// "부산대" UTF-8 percent 인코딩 — RestClient 가 queryParam 값을 인코딩해 내보내는 것까지 계약으로 고정한다
	private static final String KEYWORD_URL =
		BASE_URL + "/v2/local/search/keyword.json?query=%EB%B6%80%EC%82%B0%EB%8C%80&size=15";
	// 부산 서면 지도 중심 — 카카오는 x 가 경도, y 가 위도다 (MSG-481 §D6)
	private static final double CENTER_LAT = 35.1578;
	private static final double CENTER_LNG = 129.0594;
	private static final String NEARBY_URL = KEYWORD_URL + "&x=129.0594&y=35.1578&radius=20000";
	private static final String ONE_PLACE_BODY = """
		{ "documents": [ { "place_name": "서면역", "address_name": "부산 부산진구 부전동",
			"road_address_name": "부산 부산진구 중앙대로", "x": "129.05930", "y": "35.15790" } ] }
		""";

	private MockRestServiceServer server;
	private KakaoLocalClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder
			.baseUrl(BASE_URL)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + REST_API_KEY)
			.build();
		client = new KakaoLocalClient(restClient);
	}

	// 검증: FR-SEARCH-02, FR-SEARCH-03
	@Test
	void 키워드_검색은_KakaoAK_헤더와_query_size15로_요청한다() {
		server.expect(requestTo(KEYWORD_URL))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK " + REST_API_KEY))
			.andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

		client.search("부산대");

		server.verify();
	}

	// 검증: FR-SEARCH-16
	@Test
	void 좌표가_있으면_x_y_radius20000을_실어_요청한다() {
		server.expect(requestTo(NEARBY_URL))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(ONE_PLACE_BODY, MediaType.APPLICATION_JSON));

		client.search("부산대", CENTER_LAT, CENTER_LNG);

		server.verify();
	}

	// 검증: FR-SEARCH-16
	@Test
	void 좌표가_있어도_sort는_보내지_않는다() {
		// 반경은 후보 집합을 좁히는 데만 쓰고 그 안의 순서는 카카오 기본 정확도순을 그대로 따른다 (§D6)
		server.expect(requestTo(not(containsString("sort"))))
			.andRespond(withSuccess(ONE_PLACE_BODY, MediaType.APPLICATION_JSON));

		client.search("부산대", CENTER_LAT, CENTER_LNG);

		server.verify();
	}

	// 검증: FR-SEARCH-16
	@Test
	void 근처_결과가_0건이면_위치_없이_한_번_재호출한다() {
		server.expect(requestTo(NEARBY_URL))
			.andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withSuccess(ONE_PLACE_BODY, MediaType.APPLICATION_JSON));

		List<KakaoPlace> places = client.search("부산대", CENTER_LAT, CENTER_LNG);

		// 반환값은 폴백(전국) 응답이다 — 근처 0건이라고 빈 리스트가 나가면 검색이 지금보다 나빠진다 (FR-2)
		assertThat(places).extracting(KakaoPlace::placeName).containsExactly("서면역");
		server.verify();
	}

	// 검증: FR-SEARCH-16
	@Test
	void 근처_결과가_있으면_재호출하지_않는다() {
		server.expect(ExpectedCount.once(), requestTo(NEARBY_URL))
			.andRespond(withSuccess(ONE_PLACE_BODY, MediaType.APPLICATION_JSON));

		assertThat(client.search("부산대", CENTER_LAT, CENTER_LNG)).hasSize(1);

		server.verify();   // 기대 1건뿐이라 두 번째 호출이 있었다면 여기서 걸린다
	}

	// 검증: FR-SEARCH-16
	@Test
	void 폴백_결과도_0건이면_빈_리스트이고_세_번째_호출은_없다() {
		server.expect(requestTo(NEARBY_URL))
			.andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

		assertThat(client.search("부산대", CENTER_LAT, CENTER_LNG)).isEmpty();

		server.verify();
	}

	// 검증: FR-SEARCH-16
	@Test
	void 근처_호출이_실패하면_폴백_없이_5502로_수렴한다() {
		// 실패는 두 번째 호출에서도 대개 재현되고, 재호출하면 대기만 두 배가 된다 (§D5)
		server.expect(ExpectedCount.once(), requestTo(NEARBY_URL))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> client.search("부산대", CENTER_LAT, CENTER_LNG))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(SearchErrorCode.SEARCH_UPSTREAM_ERROR));

		server.verify();
	}

	// 검증: FR-SEARCH-01
	@Test
	void documents를_장소_결과로_매핑한다() {
		// 카카오는 좌표(x/y)를 문자열로 준다 — double 파싱까지가 어댑터 계약이다 (x→lng, y→lat)
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withSuccess("""
				{ "documents": [ {
					"place_name": "부산대학교",
					"address_name": "부산 금정구 장전동 40",
					"road_address_name": "부산 금정구 부산대학로 63번길 2",
					"x": "129.08246",
					"y": "35.23272"
				} ], "meta": { "total_count": 1, "is_end": true } }
				""", MediaType.APPLICATION_JSON));

		List<KakaoPlace> places = client.search("부산대");

		assertThat(places).containsExactly(new KakaoPlace(
			"부산대학교", "부산 금정구 장전동 40", "부산 금정구 부산대학로 63번길 2", 35.23272, 129.08246));
	}

	@Test
	void 도로명주소_누락은_빈_문자열로_정규화한다() {
		// 키 자체 누락과 명시 null 둘 다 "" 로 — 서비스의 §D2 주소 규칙(isEmpty 분기)이 null 분기 없이 서게 한다
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withSuccess("""
				{ "documents": [
					{ "place_name": "누락", "address_name": "지번1", "x": "129.0", "y": "35.0" },
					{ "place_name": "널", "address_name": "지번2", "road_address_name": null, "x": "129.0", "y": "35.0" }
				] }
				""", MediaType.APPLICATION_JSON));

		List<KakaoPlace> places = client.search("부산대");

		assertThat(places).extracting(KakaoPlace::roadAddressName).containsExactly("", "");
	}

	// 검증: FR-SEARCH-02
	@Test
	void 결과가_없으면_빈_리스트다() {
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withSuccess("{\"documents\":[],\"meta\":{\"total_count\":0}}", MediaType.APPLICATION_JSON));

		assertThat(client.search("부산대")).isEmpty();
	}

	// 검증: FR-SEARCH-04
	@Test
	void 카카오_5xx면_5502_업스트림_예외다() {
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertUpstreamError();
	}

	// 검증: FR-SEARCH-04
	@Test
	void 카카오_4xx도_5502로_수렴한다() {
		// 401 = REST 키 문제 — 사용자 입력 잘못이 아니라 업스트림 실패이므로 400 계열로 새지 않고 5502 로 수렴한다(§D3)
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertUpstreamError();
	}

	// 검증: FR-SEARCH-04
	@Test
	void 업스트림_실패_로그에_검색어_원문이_남지_않는다() {
		// 응답 본문이 검색어를 에코해도 로그로 새지 않는다 (MSG-342 D-2) — 예외 메시지가 본문을 포함하기 때문
		ListAppender<ILoggingEvent> logAppender = attachLogAppender();
		try {
			server.expect(requestTo(startsWith(BASE_URL + "/v2/local/search/keyword.json")))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
					.body("{\"error\":\"query 강남 맛집 처리 실패\"}"));

			ApiException thrown = catchThrowableOfType(ApiException.class, () -> client.search("강남 맛집"));

			// 원인 구분 손잡이는 예외 클래스명 + 상태 코드로 유지된다
			assertThat(logAppender.list.get(0).getFormattedMessage())
				.doesNotContain("강남")
				.contains(thrown.getCause().getClass().getSimpleName())
				.contains("status=500");
		} finally {
			detachLogAppender(logAppender);
		}
	}

	@Test
	void 타임아웃_실패_로그에_요청_URL이_남지_않는다() {
		// ResourceAccessException 메시지는 요청 URL 전체(?query=검색어)를 포함한다 — 항상 유출 경로 (MSG-342 D-2)
		ListAppender<ILoggingEvent> logAppender = attachLogAppender();
		try {
			server.expect(requestTo(startsWith(BASE_URL + "/v2/local/search/keyword.json")))
				.andRespond(withException(new SocketTimeoutException("read timed out")));

			assertThatThrownBy(() -> client.search("강남 맛집")).isInstanceOf(ApiException.class);

			assertThat(logAppender.list.get(0).getFormattedMessage())
				.doesNotContain("강남")
				.doesNotContain("query=")
				.contains("ResourceAccessException");
		} finally {
			detachLogAppender(logAppender);
		}
	}

	private static ListAppender<ILoggingEvent> attachLogAppender() {
		ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
		logAppender.start();
		clientLogger().addAppender(logAppender);
		return logAppender;
	}

	private static void detachLogAppender(ListAppender<ILoggingEvent> logAppender) {
		clientLogger().detachAppender(logAppender);
	}

	private static Logger clientLogger() {
		return (Logger) LoggerFactory.getLogger(KakaoLocalClient.class);
	}

	@Test
	void 타임아웃이면_5502_업스트림_예외다() {
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withException(new SocketTimeoutException("read timed out")));

		assertUpstreamError();
	}

	// 검증: FR-SEARCH-04
	@Test
	void documents_배열이_없는_2xx_응답은_5502로_수렴한다() {
		// {} 같은 기형 성공 응답 — 빈 결과 200 으로 위장되면 스키마 변경을 못 잡는다(§D3, Codex P2)
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		assertUpstreamError();
	}

	// 검증: FR-SEARCH-04
	@Test
	void documents가_배열이_아니면_5502로_수렴한다() {
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withSuccess("{\"documents\":null}", MediaType.APPLICATION_JSON));

		assertUpstreamError();
	}

	// 검증: FR-SEARCH-04
	@Test
	void 본문이_JSON이_아니면_5502로_수렴한다() {
		// 게이트웨이 장애 페이지(HTML) 등 — 파싱 실패도 업스트림 실패다(§D3)
		server.expect(requestTo(KEYWORD_URL))
			.andRespond(withSuccess("<html>Bad Gateway</html>", MediaType.TEXT_HTML));

		assertUpstreamError();
	}

	private void assertUpstreamError() {
		assertThatThrownBy(() -> client.search("부산대"))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(SearchErrorCode.SEARCH_UPSTREAM_ERROR));
	}
}
