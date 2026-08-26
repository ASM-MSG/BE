package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import com.msg.fillmap.route.config.RouteWalkProperties;
import com.msg.fillmap.route.service.TmapWalkClient.Coordinate;
import com.msg.fillmap.route.service.TmapWalkClient.WalkPath;

/**
 * TMap 보행자 경로안내 어댑터를 MockRestServiceServer 로 스텁해 계약 매핑과 형태 검증을 확인한다
 * (MSG-483 — RouteIntentClientTest 선례 구도). 응답 형태(FeatureCollection, 첫 Point 의 totalDistance,
 * LineString 경도-우선)는 2026-08-26 실호출 1건으로 대조한 실측 기준이다. 실패 구분이 핵심 계약이다:
 * 상태 코드가 있는 실패와 형태 위반은 null(그 세그먼트만 실패), 응답 자체가 없는 실패는 단락 예외.
 */
@DisplayName("TmapWalkClient — TMap 보행자 경로안내 계약 소비")
class TmapWalkClientTest {

	private static final String BASE_URL = "https://tmap.test";
	private static final String PEDESTRIAN_URL = BASE_URL + "/tmap/routes/pedestrian?version=1";

	// 실측 응답 요약형 — 첫 Point(SP)만 totalDistance 를 가진다 (2026-08-26 실호출: 888m, 13 LineString).
	private static final String 출발_POINT = """
		{"type":"Feature","geometry":{"type":"Point","coordinates":[129.1604,35.1587]},
		 "properties":{"pointType":"SP","totalDistance":888,"totalTime":726}}""";
	private static final String 경유_POINT = """
		{"type":"Feature","geometry":{"type":"Point","coordinates":[129.1611,35.1589]},
		 "properties":{"pointType":"GP"}}""";

	private RouteWalkProperties properties() {
		return new RouteWalkProperties(true, BASE_URL, "test-app-key", 900, Duration.ofSeconds(3));
	}

	/** 케이스 전용 클라이언트 — 서버 기대를 걸고 호출 하나를 실행한다 (RouteIntentClientTest 선례). */
	private TmapWalkClient client(Consumer<MockRestServiceServer> expectation) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		expectation.accept(server);
		return new TmapWalkClient(builder, properties());
	}

	private DefaultResponseCreator json(String body) {
		return withSuccess(body, MediaType.APPLICATION_JSON);
	}

	private String featureCollection(String... features) {
		return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
	}

	private String lineString(String positions) {
		return "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[" + positions
			+ "]},\"properties\":{\"distance\":100}}";
	}

	private void 형태_위반이_세그먼트_실패가_된다(String body) {
		TmapWalkClient client = client(server ->
			server.expect(requestTo(PEDESTRIAN_URL)).andRespond(json(body)));
		assertThat(client.fetch(35.1587, 129.1604, 35.1631, 129.1635)).isNull();
	}

	@Nested
	@DisplayName("성공 매핑")
	class Success {

		// 검증: FR-ROUTE-16, NFR-SEC-10
		@Test
		@DisplayName("요청은 경도-우선(startX=경도)·WGS84GEO·appKey 헤더, 응답은 위도-경도로 뒤집어 매핑한다")
		void 정상_응답을_보행_좌표열과_실거리로_매핑한다() {
			TmapWalkClient client = client(server ->
				server.expect(requestTo(PEDESTRIAN_URL))
					.andExpect(method(HttpMethod.POST))
					.andExpect(header("appKey", "test-app-key"))
					// strict json 비교 — 명시한 필드 집합과 정확히 일치해야 통과라, 여분 필드가 실리면 실패한다.
					.andExpect(content().json("""
						{"startX": 129.1604, "startY": 35.1587, "endX": 129.1635, "endY": 35.1631,
						 "startName": "출발", "endName": "도착",
						 "reqCoordType": "WGS84GEO", "resCoordType": "WGS84GEO"}
						""", JsonCompareMode.STRICT))
					.andRespond(json(featureCollection(
						출발_POINT,
						lineString("[129.1604,35.1587],[129.1611,35.1589]"),
						경유_POINT,
						lineString("[129.1611,35.1589],[129.1635,35.1631]")))));

			WalkPath walkPath = client.fetch(35.1587, 129.1604, 35.1631, 129.1635);

			assertThat(walkPath.distanceMeters()).isEqualTo(888);
			// LineString 들을 순서대로 이어붙인다 — 접합점 중복 제거 없이 문자 그대로 (스펙 문면).
			assertThat(walkPath.path()).containsExactly(
				new Coordinate(35.1587, 129.1604),
				new Coordinate(35.1589, 129.1611),
				new Coordinate(35.1589, 129.1611),
				new Coordinate(35.1631, 129.1635));
		}
	}

	@Nested
	@DisplayName("형태 검증 — 위반은 null (그 세그먼트만 실패)")
	class ContractViolation {

		// 검증: FR-ROUTE-17
		@Test
		@DisplayName("totalDistance 누락·음수, 좌표열 없음·2점 미만, 정의역 밖, 5,000점 초과 전부 실패 처리")
		void TMap_응답_형태가_계약과_다르면_그_세그먼트만_실패한다() {
			String 좌표열 = "[129.1604,35.1587],[129.1611,35.1589]";
			// 첫 Point 에 totalDistance 없음
			형태_위반이_세그먼트_실패가_된다(featureCollection(경유_POINT, lineString(좌표열)));
			// totalDistance 음수
			형태_위반이_세그먼트_실패가_된다(featureCollection(
				출발_POINT.replace("\"totalDistance\":888", "\"totalDistance\":-1"), lineString(좌표열)));
			// 거리만 있고 LineString feature 가 없다 — 선을 그릴 수 없는 응답 (형태 검증 신설 항목)
			형태_위반이_세그먼트_실패가_된다(featureCollection(출발_POINT));
			// 이어붙인 좌표열 총 점이 2개 미만
			형태_위반이_세그먼트_실패가_된다(featureCollection(출발_POINT, lineString("[129.1604,35.1587]")));
			// LineString 좌표가 정의역 밖 (위도 95)
			형태_위반이_세그먼트_실패가_된다(featureCollection(
				출발_POINT, lineString("[129.1604,35.1587],[129.1611,95.0]")));
			// features 가 배열이 아니다
			형태_위반이_세그먼트_실패가_된다("{\"type\":\"FeatureCollection\",\"features\":{}}");
		}

		// 검증: FR-ROUTE-17
		@Test
		void 좌표열_총_점수가_5000점을_초과하면_실패_처리한다() {
			StringBuilder positions = new StringBuilder();
			for (int i = 0; i <= 5000; i++) {
				if (i > 0) {
					positions.append(',');
				}
				positions.append("[127.0,37.0]");
			}
			형태_위반이_세그먼트_실패가_된다(featureCollection(출발_POINT, lineString(positions.toString())));
		}
	}

	@Nested
	@DisplayName("호출 실패 구분")
	class Failure {

		// 검증: FR-ROUTE-17
		@Test
		@DisplayName("상태 코드가 있는 실패는 null — 호출자가 다음 세그먼트를 계속 진행한다")
		void 상태_코드가_있는_실패는_그_세그먼트만_실패한다() {
			TmapWalkClient client = client(server ->
				server.expect(requestTo(PEDESTRIAN_URL)).andRespond(withServerError()));
			assertThat(client.fetch(35.1587, 129.1604, 35.1631, 129.1635)).isNull();
		}

		// 검증: FR-ROUTE-17
		@Test
		@DisplayName("응답 자체가 없는 실패는 단락 예외 — 호출자가 남은 미스 세그먼트를 호출 없이 접는다")
		void 연결_실패나_타임아웃은_요청_내_단락_예외를_던진다() {
			TmapWalkClient client = client(server ->
				server.expect(requestTo(PEDESTRIAN_URL))
					.andRespond(withException(new SocketTimeoutException("read timed out"))));
			assertThatThrownBy(() -> client.fetch(35.1587, 129.1604, 35.1631, 129.1635))
				.isInstanceOf(TmapWalkClient.TmapUnreachableException.class);
		}
	}
}
