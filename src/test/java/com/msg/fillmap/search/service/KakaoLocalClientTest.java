package com.msg.fillmap.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
