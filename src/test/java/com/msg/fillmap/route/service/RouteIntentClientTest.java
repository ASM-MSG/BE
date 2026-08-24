package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.route.config.RouteAiProperties;
import com.msg.fillmap.route.exception.RouteErrorCode;
import com.msg.fillmap.route.service.RouteIntentClient.ExplainPoint;
import com.msg.fillmap.route.service.RouteIntentClient.ParsedIntent;
import com.msg.fillmap.route.service.RouteIntentClient.Viewport;

/**
 * AI 경로 해석 어댑터를 MockRestServiceServer 로 스텁해 계약 매핑과 형태 검증을 확인한다 (MSG-457 —
 * KakaoLocalClientTest 선례 구도). BE 형태 검증 목록은 AI 계약과 1:1 이고 위반은 전부 14502 단일 수렴이다.
 * 케이스마다 새 builder 에 bind 해 기대 1회씩만 건다 — 예외 케이스가 뒤 기대를 오염시키지 않게 한다.
 */
@DisplayName("RouteIntentClient — AI 경로 해석 계약 소비 (parse·explain)")
class RouteIntentClientTest {

	private static final String BASE_URL = "https://route-ai.test";
	private static final Viewport 부산_뷰포트 = new Viewport(35.05, 128.95, 35.25, 129.20);
	private static final List<ExplainPoint> 지점_2개 = List.of(
		new ExplainPoint("해운대 빛축제", "mission_festival", List.of("8월 축제 미션 후보")),
		new ExplainPoint("광안리 해변", "place", List.of("장소 검색 결과")));

	private RouteAiProperties properties() {
		return new RouteAiProperties(true, BASE_URL, Duration.ofSeconds(10));
	}

	/** 케이스 전용 클라이언트 — 서버 기대를 걸고 호출 하나를 실행한 뒤 verify 까지 한다. */
	private RouteIntentClient client(Consumer<MockRestServiceServer> expectation) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		expectation.accept(server);
		return new RouteIntentClient(builder, properties());
	}

	private DefaultResponseCreator json(String body) {
		return withSuccess(body, MediaType.APPLICATION_JSON);
	}

	private void parse가_거부된다(String body) {
		RouteIntentClient client = client(server ->
			server.expect(requestTo(BASE_URL + "/route/parse")).andRespond(json(body)));
		assertThatThrownBy(() -> client.parse("해운대 가자", 부산_뷰포트))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_AI_UNAVAILABLE);
	}

	private void explain이_거부된다(String body) {
		RouteIntentClient client = client(server ->
			server.expect(requestTo(BASE_URL + "/route/explain")).andRespond(json(body)));
		assertThatThrownBy(() -> client.explain(지점_2개))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_AI_UNAVAILABLE);
	}

	@Nested
	@DisplayName("parse — 자연어 해석")
	class Parse {

		// 검증: NFR-SEC-09
		@Test
		@DisplayName("요청 본문은 text 와 snake_case 뷰포트뿐이다 — 사용자 식별 정보 필드가 없다")
		void AI_요청_본문에_사용자_식별_정보가_없다() {
			// strict json 비교 — 명시한 필드 집합과 정확히 일치해야 통과라, 여분 필드가 실리면 실패한다.
			RouteIntentClient client = client(server ->
				server.expect(requestTo(BASE_URL + "/route/parse"))
					.andExpect(method(HttpMethod.POST))
					.andExpect(content().json("""
						{"text": "해운대 가자",
						 "viewport": {"min_lat": 35.05, "min_lng": 128.95, "max_lat": 35.25, "max_lng": 129.20}}
						""", JsonCompareMode.STRICT))
					.andRespond(json(
						"{\"region\": null, \"period\": null, \"interests\": [], \"preferred_order\": []}")));

			client.parse("해운대 가자", 부산_뷰포트);
		}

		// 검증: FR-ROUTE-01
		@Test
		void 정상_응답을_해석_결과로_매핑한다() {
			RouteIntentClient client = client(server ->
				server.expect(requestTo(BASE_URL + "/route/parse")).andRespond(json("""
					{"region": "해운대",
					 "period": {"start": "2026-08-29", "end": "2026-08-30"},
					 "interests": ["축제", "밥"],
					 "preferred_order": ["부산역", "해운대"]}
					""")));

			ParsedIntent intent = client.parse("부산역 내려서 해운대에서 밥 먹고 축제도 보고 싶어", 부산_뷰포트);

			assertThat(intent.region()).isEqualTo("해운대");
			assertThat(intent.period()).isEqualTo(
				new ParsedIntent.Period(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 30)));
			assertThat(intent.interests()).containsExactly("축제", "밥");
			assertThat(intent.preferredOrder()).containsExactly("부산역", "해운대");
		}

		// 검증: FR-ROUTE-06
		@Test
		void 전_필드가_빈_해석도_유효_응답이다() {
			RouteIntentClient client = client(server ->
				server.expect(requestTo(BASE_URL + "/route/parse"))
					.andRespond(json("""
						{"region": null, "period": null, "interests": [], "preferred_order": []}
						""")));

			ParsedIntent intent = client.parse("아무거나", 부산_뷰포트);

			assertThat(intent.region()).isNull();
			assertThat(intent.period()).isNull();
			assertThat(intent.interests()).isEmpty();
			assertThat(intent.preferredOrder()).isEmpty();
		}

		// 검증: NFR-SEC-08, FR-ROUTE-08
		@Test
		@DisplayName("형태를 벗어난 parse 응답은 채택하지 않는다 — BE 검증 목록의 각 규칙 위반이 전부 14502")
		void 형태를_벗어난_parse_응답은_채택하지_않는다() {
			parse가_거부된다("{}");                                                          // 전 필드 누락 (Codex P2)
			parse가_거부된다("{\"region\": null, \"period\": null, \"interests\": []}");    // preferred_order 누락
			parse가_거부된다("{\"region\": \"해운대\", \"bogus\": 1}");                       // 정의 밖 필드
			parse가_거부된다("{\"region\": \"" + "가".repeat(51) + "\"}");                   // region 51자
			parse가_거부된다("{\"region\": 123}");                                          // region 비문자열
			parse가_거부된다("{\"period\": {\"start\": \"2026-09-02\", \"end\": \"2026-09-01\"}}"); // start > end
			parse가_거부된다("{\"period\": {\"start\": \"어제\", \"end\": \"2026-09-01\"}}");       // 날짜 형식
			parse가_거부된다("{\"period\": {\"start\": \"2026-09-01\"}}");                          // end 누락
			parse가_거부된다("{\"period\": {\"start\": \"2026-09-01\", \"end\": \"2026-09-02\", \"tz\": \"KST\"}}");
			parse가_거부된다("{\"interests\": [" + "\"축제\",".repeat(10) + "\"밥\"]}");     // 11개
			parse가_거부된다("{\"interests\": [\"" + "가".repeat(51) + "\"]}");              // 항목 51자
			parse가_거부된다("{\"preferred_order\": \"광안리\"}");                           // 비배열
		}
	}

	@Nested
	@DisplayName("explain — 추천 이유 문장화")
	class Explain {

		// 검증: FR-ROUTE-05
		@Test
		void reasons를_points와_같은_순서로_돌려준다() {
			RouteIntentClient client = client(server ->
				server.expect(requestTo(BASE_URL + "/route/explain"))
					.andExpect(content().json("""
						{"points": [
							{"name": "해운대 빛축제", "kind": "mission_festival", "facts": ["8월 축제 미션 후보"]},
							{"name": "광안리 해변", "kind": "place", "facts": ["장소 검색 결과"]}]}
						""", JsonCompareMode.STRICT))
					.andRespond(json("{\"reasons\": [\"저녁 일정으로 맞습니다.\", \"도보로 이어집니다.\"]}")));

			assertThat(client.explain(지점_2개))
				.containsExactly("저녁 일정으로 맞습니다.", "도보로 이어집니다.");
		}

		// 검증: NFR-SEC-08, FR-ROUTE-08
		@Test
		@DisplayName("형태를 벗어난 explain 응답은 채택하지 않는다 — 개수·순서·개행·빈 문자열·길이 규칙")
		void 형태를_벗어난_explain_응답은_채택하지_않는다() {
			explain이_거부된다("{\"reasons\": [\"하나뿐\"]}");                               // 개수 불일치
			explain이_거부된다("{\"reasons\": [\"줄\\n바꿈\", \"정상\"]}");                  // 개행 포함
			explain이_거부된다("{\"reasons\": [\"\", \"정상\"]}");                           // 빈 문자열
			explain이_거부된다("{\"reasons\": [\"" + "가".repeat(121) + "\", \"정상\"]}");   // 121자
			explain이_거부된다("{\"reasons\": [1, \"정상\"]}");                              // 비문자열 항목
			explain이_거부된다("{}");                                                        // reasons 누락
		}
	}

	@Nested
	@DisplayName("실패 단일 수렴")
	class Failure {

		// 검증: FR-ROUTE-08
		@Test
		@DisplayName("502·503·504·타임아웃 전부 14502 로 수렴한다 — 부분 채택·지어낸 대체 결과 없음")
		void AI_실패는_결과를_지어내지_않고_14502로_수렴한다() {
			for (HttpStatus status : List.of(
				HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT)) {
				RouteIntentClient client = client(server ->
					server.expect(requestTo(BASE_URL + "/route/parse")).andRespond(withStatus(status)));
				assertThatThrownBy(() -> client.parse("해운대 가자", 부산_뷰포트))
					.isInstanceOf(ApiException.class)
					.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_AI_UNAVAILABLE);
			}

			RouteIntentClient timedOut = client(server ->
				server.expect(requestTo(BASE_URL + "/route/parse"))
					.andRespond(withException(new SocketTimeoutException("read timed out"))));
			assertThatThrownBy(() -> timedOut.parse("해운대 가자", 부산_뷰포트))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", RouteErrorCode.ROUTE_AI_UNAVAILABLE);
		}
	}
}
