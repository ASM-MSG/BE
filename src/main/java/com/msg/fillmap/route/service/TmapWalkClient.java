package com.msg.fillmap.route.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;

import com.msg.fillmap.route.config.RouteWalkProperties;

/**
 * TMap 보행자 경로안내 어댑터 (MSG-483). route.walk.enabled 일 때만 빈으로 뜬다 — 소비처는
 * ObjectProvider 로 받고 부재 시 14504 를 던진다 (RouteIntentClient 선례). appKey 는 서버 설정 주입이고
 * 어떤 로그와 응답에도 싣지 않는다 (NFR-SEC-10).
 *
 * 실패는 두 갈래로 구분한다 (§도메인 로직 3): 상태 코드가 있는 실패(4xx·5xx)와 형태 위반은 null 로
 * 그 세그먼트만 실패시키고, 연결 실패·타임아웃(응답 자체가 없는 실패)은 TmapUnreachableException 으로
 * 호출자가 남은 미스 세그먼트를 호출 없이 단락하게 한다 — TMap 이 죽은 상태에서 최악 지연이
 * 타임아웃의 세그먼트 수 배가 되는 것을 1회로 묶는다.
 *
 * 응답은 GeoJSON FeatureCollection 이다 — 첫 Point feature 의 properties.totalDistance(미터)가 실거리,
 * LineString feature 들의 coordinates 를 순서대로 이어붙인 것이 좌표열이다. GeoJSON 은 경도-우선이라
 * 위도-경도로 뒤집어 돌려준다. 형태는 2026-08-26 실호출 1건으로 대조했다 (스펙 §TMap 호출).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "route.walk", name = "enabled")
public class TmapWalkClient {

	// 스펙 §TMap 호출 — 연결 1초, 교환 전체는 timeout(PT3S). RouteIntentClient(연결 2초)보다 짧은 것은
	// 외부망 단건 호출이 최대 8세그먼트 순차라 요청 전체 지연에 배수로 실리기 때문이다.
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
	private static final String PEDESTRIAN_URI = "/tmap/routes/pedestrian?version=1";
	private static final int MAX_PATH_POINTS = 5000;

	private final RestClient restClient;

	@Autowired
	public TmapWalkClient(RouteWalkProperties properties) {
		this(RestClient.builder().requestFactory(requestFactory(properties.timeout())), properties);
	}

	/** 테스트 진입점 — MockRestServiceServer 바인딩용 (RouteIntentClient 선례). */
	TmapWalkClient(RestClient.Builder builder, RouteWalkProperties properties) {
		this.restClient = builder
			.baseUrl(properties.baseUrl())
			.defaultHeader("appKey", properties.appKey())
			.build();
	}

	/**
	 * java.net.http 기반 팩토리 (RouteIntentClient 선례). readTimeout 이 HttpRequest.timeout() 으로 걸려
	 * 교환 전체(전송+처리+수신)의 단일 시한이 된다.
	 */
	static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(timeout);
		return factory;
	}

	/**
	 * 세그먼트 1건의 보행 경로 조회. 성공이면 좌표열(위도-경도)과 실거리, 상태 코드가 있는 실패와 형태
	 * 위반이면 null(그 세그먼트만 실패), 응답 자체가 없는 실패면 TmapUnreachableException(요청 내 단락).
	 */
	public WalkPath fetch(double startLat, double startLng, double endLat, double endLng) {
		JsonNode response;
		try {
			response = restClient.post()
				.uri(PEDESTRIAN_URI)
				.contentType(MediaType.APPLICATION_JSON)
				// startX 는 경도, startY 는 위도다 — TMap 좌표 표기 (스펙 §TMap 호출, 테스트가 strict 로 고정).
				.body(Map.of(
					"startX", startLng, "startY", startLat,
					"endX", endLng, "endY", endLat,
					"startName", "출발", "endName", "도착",
					"reqCoordType", "WGS84GEO", "resCoordType", "WGS84GEO"))
				.retrieve()
				.body(JsonNode.class);
		} catch (ResourceAccessException e) {
			// 로그에 좌표 값을 남기지 않는다 — 클래스명만 (KakaoLocalClient 선례, 지표 로그 규칙).
			log.warn("[route-walk] TMap 연결 실패·타임아웃 — 요청 내 단락: cause={}", e.getClass().getSimpleName());
			throw new TmapUnreachableException(e);
		} catch (RestClientException e) {
			String status = e instanceof RestClientResponseException responseException
				? String.valueOf(responseException.getStatusCode().value())
				: "-";
			log.warn("[route-walk] TMap 호출 실패 — 세그먼트 실패 처리: cause={}, status={}",
				e.getClass().getSimpleName(), status);
			return null;
		}
		return toWalkPath(response);
	}

	/**
	 * 형태 검증 — 신뢰 경계 원칙 (NFR-SEC-08 의 BE 몫). totalDistance 가 없거나 음수, LineString 이 없거나
	 * 총 점이 2개 미만(거리만 있고 선을 그릴 수 없는 응답), 좌표가 WGS84 정의역 밖, 총 점수 5,000점 초과는
	 * 전부 위반으로 null 을 돌려준다.
	 */
	private WalkPath toWalkPath(JsonNode response) {
		if (response == null) {
			return violation("응답 본문 없음");
		}
		JsonNode features = response.path("features");
		if (!features.isArray()) {
			return violation("features 배열 아님");
		}
		Integer distanceMeters = null;
		List<Coordinate> path = new ArrayList<>();
		for (JsonNode feature : features) {
			JsonNode geometry = feature.path("geometry");
			JsonNode typeNode = geometry.path("type");
			String type = typeNode.isString() ? typeNode.asString() : "";
			if (distanceMeters == null && "Point".equals(type)) {
				// 실측 대조: totalDistance 는 첫 Point(pointType SP)에만 실린다 — 첫 Point 에서 없으면 위반.
				JsonNode totalDistance = feature.path("properties").path("totalDistance");
				if (!totalDistance.isNumber() || totalDistance.asInt() < 0) {
					return violation("totalDistance 누락·음수");
				}
				distanceMeters = totalDistance.asInt();
			} else if ("LineString".equals(type)) {
				if (!appendPositions(geometry.path("coordinates"), path)) {
					return violation("LineString 좌표 형식·정의역·점수 상한 위반");
				}
			}
		}
		if (distanceMeters == null) {
			return violation("totalDistance 누락 (Point feature 없음)");
		}
		if (path.size() < 2) {
			return violation("좌표열 총 점 2개 미만");
		}
		return new WalkPath(List.copyOf(path), distanceMeters);
	}

	/** GeoJSON position([경도, 위도])을 위도-경도로 뒤집어 누적한다. 위반이면 false. */
	private boolean appendPositions(JsonNode coordinates, List<Coordinate> path) {
		if (!coordinates.isArray()) {
			return false;
		}
		for (JsonNode position : coordinates) {
			if (!position.isArray() || position.size() < 2
				|| !position.get(0).isNumber() || !position.get(1).isNumber()) {
				return false;
			}
			double lng = position.get(0).asDouble();
			double lat = position.get(1).asDouble();
			if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
				return false;
			}
			if (path.size() >= MAX_PATH_POINTS) {
				return false;
			}
			path.add(new Coordinate(lat, lng));
		}
		return true;
	}

	/** 형태 위반은 세그먼트 실패(null) — 위반 지점만 남기고 응답 본문·좌표 값은 로그에 남기지 않는다. */
	private WalkPath violation(String what) {
		log.warn("[route-walk] TMap 응답 형태 위반 — 세그먼트 실패 처리: {}", what);
		return null;
	}

	/** 보행 경로 결과 — path 는 위도-경도 순(GeoJSON 경도-우선을 어댑터가 뒤집는다), 거리는 미터. */
	public record WalkPath(List<Coordinate> path, int distanceMeters) {
	}

	public record Coordinate(double lat, double lng) {
	}

	/** 응답 자체가 없는 실패(연결·타임아웃)의 단락 신호 — 호출자가 남은 미스 세그먼트를 접는다. */
	public static class TmapUnreachableException extends RuntimeException {

		TmapUnreachableException(Throwable cause) {
			super(cause);
		}
	}
}
