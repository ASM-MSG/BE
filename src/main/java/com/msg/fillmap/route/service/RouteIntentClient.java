package com.msg.fillmap.route.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.route.config.RouteAiProperties;
import com.msg.fillmap.route.exception.RouteErrorCode;

/**
 * FillMap-AI 경로 해석 서버 어댑터 (MSG-457 소비, 계약 정본은 FillMap-AI MSG-458). route.ai.enabled 일 때만
 * 빈으로 뜬다 — 소비처는 ObjectProvider 로 받는다. video/AiClient 와 별개 클라이언트·별개 설정이다(PRD 확정).
 *
 * 실패 수용은 단일 수렴이다: AI 의 502(모델 실패)·503(AI 쪽 플래그 꺼짐)·504(모델 타임아웃), BE 쪽 연결
 * 실패와 타임아웃, 그리고 BE 형태 검증 위반 전부가 14502 다 — 부분 채택이나 지어낸 대체 결과는 없다
 * (FR-ROUTE-08). 형태 검증 목록은 계약 전체와 1:1 이다: AI 서버가 같은 검증을 이미 하지만 외부 응답을
 * 신뢰 경계 안으로 들이지 않는다 (NFR-SEC-08 의 BE 몫).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "route.ai", name = "enabled")
public class RouteIntentClient {

	// AiClient highlightRequestFactory 선례 — readTimeout 은 교환 전체(전송+처리+수신)의 단일 시한이라
	// 트리클 응답에도 timeout(PT10S)이 하드 상한이다. connect 는 내부망 전제의 짧은 고정값.
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

	// AI 계약(MSG-458)의 형태 상한 — parse 문자열 필드 50자, 배열 10개, explain reason 1~120자.
	private static final int MAX_PARSE_TEXT_LENGTH = 50;
	private static final int MAX_PARSE_LIST_ITEMS = 10;
	private static final int MAX_REASON_LENGTH = 120;
	// summary 는 동선 전체 서술이라 지점별 한 줄(120자)의 두 배가 상한이다 (MSG-539 결정 2).
	private static final int MAX_SUMMARY_LENGTH = 240;
	// related 는 MSG-533 배포 확인(2026-09-01) 후 필수로 승격됐다 — 부재는 다른 네 키와 같은 형태 위반이다.
	private static final Set<String> PARSE_FIELDS =
		Set.of("region", "period", "interests", "preferred_order", "related");
	private static final Set<String> PERIOD_FIELDS = Set.of("start", "end");

	private final RestClient restClient;

	@Autowired
	public RouteIntentClient(RouteAiProperties properties) {
		this(RestClient.builder().requestFactory(requestFactory(properties.timeout())), properties);
	}

	/** 테스트 진입점 — MockRestServiceServer 바인딩용 (AiClient 선례). */
	RouteIntentClient(RestClient.Builder builder, RouteAiProperties properties) {
		this.restClient = builder.baseUrl(properties.baseUrl()).build();
	}

	/**
	 * java.net.http 기반 팩토리 (AiClient 선례). SimpleClientHttpRequestFactory 의 read timeout 은 응답
	 * read 만 묶지만 JdkClientHttpRequestFactory 는 HttpRequest.timeout() 으로 걸려 교환 전체의 시한이 된다.
	 */
	static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(timeout);
		return factory;
	}

	/** 자연어 해석 (POST /route/parse). 빈 해석(전 필드 null·빈 배열)도 유효 응답이다 (FR-ROUTE-06). */
	public ParsedIntent parse(String text, Viewport viewport) {
		return toParsedIntent(exchange("/route/parse", new ParseRequest(text, viewport)));
	}

	/**
	 * 추천 이유 문장화 (POST /route/explain). points 0개는 AI 가 422 로 거부하는 요청이라 호출자가 만들지
	 * 않는다(빈 후보 분기는 서비스 몫). name·facts 의 100자 절단과 facts 하한 1 보장도 조립하는 호출자 몫이다.
	 *
	 * text(사용자 문장 원문, parse 와 같은 트림 본문)를 동봉한다 — 종합 이유(summary)의 재료다. 신계약은
	 * "text 가 있으면 summary 필수"라 부재도 14502 다 (MSG-540 배포 확인 후 승격, 2026-09-01).
	 */
	public ExplainResult explain(String text, List<ExplainPoint> points) {
		JsonNode response = exchange("/route/explain", Map.of("text", text, "points", points));
		return new ExplainResult(toReasons(response, points.size()), toSummary(response));
	}

	private JsonNode exchange(String uri, Object body) {
		try {
			return restClient.post()
				.uri(uri)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(JsonNode.class);
		} catch (RestClientException e) {
			// 예외 메시지에 응답 본문(사용자 문장이 되돌아올 수 있음)이 실리므로 클래스명과 상태만 남긴다
			// (KakaoLocalClient 선례 — 지표 로그 규칙 "AI 응답 원문은 로그에 남기지 않는다").
			String status = e instanceof RestClientResponseException responseException
				? String.valueOf(responseException.getStatusCode().value())
				: "-";
			log.warn("[route] AI 호출 실패 — 14502 수렴: uri={}, cause={}, status={}",
				uri, e.getClass().getSimpleName(), status);
			throw new ApiException(RouteErrorCode.ROUTE_AI_UNAVAILABLE, e);
		}
	}

	/** parse 응답 형태 검증 — 정의 밖 필드와 필수 필드(다섯 키) 누락을 모두 거부한다 (계약과 1:1, NFR-SEC-08). */
	private ParsedIntent toParsedIntent(JsonNode response) {
		if (response == null || !response.isObject()) {
			throw contractViolation("parse 응답이 JSON 객체가 아님");
		}
		for (Map.Entry<String, JsonNode> property : response.properties()) {
			if (!PARSE_FIELDS.contains(property.getKey())) {
				throw contractViolation("parse 응답에 정의 밖 필드");
			}
		}
		// 계약은 "네 필드는 항상 온다"다 — 누락도 형태 위반이라 채택하지 않는다 (NFR-SEC-08, Codex 교차 리뷰).
		for (String field : PARSE_FIELDS) {
			if (!response.has(field)) {
				throw contractViolation("parse 응답 필수 필드 누락");
			}
		}
		return new ParsedIntent(
			stringOrNull(response.path("region"), "region"),
			toPeriod(response.path("period")),
			stringList(response.path("interests"), "interests"),
			stringList(response.path("preferred_order"), "preferred_order"),
			toRelated(response.path("related")));
	}

	/** related — boolean 만 수용(null·문자열·숫자 위반). 부재는 필수 키 검사가 먼저 잡는다(MSG-533 승격). */
	private boolean toRelated(JsonNode node) {
		if (!node.isBoolean()) {
			throw contractViolation("related 가 boolean 아님");
		}
		return node.booleanValue();
	}

	/** period — null 허용, 있으면 {start, end} 유효 날짜에 start 가 end 보다 늦지 않을 것 (활성 필터 재료 방어). */
	private ParsedIntent.Period toPeriod(JsonNode node) {
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (!node.isObject()) {
			throw contractViolation("period 가 객체가 아님");
		}
		for (Map.Entry<String, JsonNode> property : node.properties()) {
			if (!PERIOD_FIELDS.contains(property.getKey())) {
				throw contractViolation("period 에 정의 밖 필드");
			}
		}
		LocalDate start = toDate(node.path("start"), "period.start");
		LocalDate end = toDate(node.path("end"), "period.end");
		if (start.isAfter(end)) {
			throw contractViolation("period 순서 위반 (start > end)");
		}
		return new ParsedIntent.Period(start, end);
	}

	private LocalDate toDate(JsonNode node, String field) {
		if (!node.isString()) {
			throw contractViolation(field + " 누락 또는 문자열 아님");
		}
		try {
			return LocalDate.parse(node.asString());
		} catch (DateTimeParseException e) {
			throw contractViolation(field + " 날짜 형식 위반");
		}
	}

	private String stringOrNull(JsonNode node, String field) {
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (!node.isString() || node.asString().length() > MAX_PARSE_TEXT_LENGTH) {
			throw contractViolation(field + " 형식·길이 위반");
		}
		return node.asString();
	}

	private List<String> stringList(JsonNode node, String field) {
		if (node.isMissingNode() || node.isNull()) {
			return List.of();
		}
		if (!node.isArray() || node.size() > MAX_PARSE_LIST_ITEMS) {
			throw contractViolation(field + " 배열 형식·개수 위반");
		}
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			if (!item.isString() || item.asString().length() > MAX_PARSE_TEXT_LENGTH) {
				throw contractViolation(field + " 항목 형식·길이 위반");
			}
			values.add(item.asString());
		}
		return values;
	}

	/** explain 응답 검증 — reasons 는 points 와 같은 개수·같은 순서의 개행 없는 1~120자 문자열 배열이다. */
	private List<String> toReasons(JsonNode response, int expectedCount) {
		if (response == null) {
			throw contractViolation("explain 응답 본문 없음");
		}
		JsonNode reasons = response.path("reasons");
		if (!reasons.isArray() || reasons.size() != expectedCount) {
			throw contractViolation("reasons 형식·개수 위반");
		}
		List<String> values = new ArrayList<>();
		for (JsonNode reason : reasons) {
			if (!reason.isString()) {
				throw contractViolation("reasons 항목이 문자열 아님");
			}
			String value = reason.asString();
			if (value.isBlank() || value.length() > MAX_REASON_LENGTH
				|| value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
				throw contractViolation("reasons 항목 규칙 위반 (빈 문자열·길이·개행)");
			}
			values.add(value);
		}
		return values;
	}

	/**
	 * summary 형태 검증 (MSG-539 결정 2) — 개행 없는 1~240자 문자열만 채택한다. 부재와 명시 null 은 둘 다
	 * 14502 다 (MSG-540 승격, 2026-09-01 — 신계약은 text 를 보낸 요청에 summary 가 필수다). 정의 밖 필드를
	 * 거부하지 않는 explain 기존 동작은 그대로다(MSG-457 검증 목록).
	 */
	private String toSummary(JsonNode response) {
		JsonNode summary = response.path("summary");
		if (summary.isMissingNode() || summary.isNull()) {
			throw contractViolation("summary 부재·null (신계약 필수)");
		}
		if (!summary.isString()) {
			throw contractViolation("summary 가 문자열 아님");
		}
		String value = summary.asString();
		if (value.isBlank() || value.length() > MAX_SUMMARY_LENGTH
			|| value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
			throw contractViolation("summary 규칙 위반 (빈 문자열·길이·개행)");
		}
		return value;
	}

	/** 형태 위반 단일 수렴(14502) — 위반 지점만 남기고 모델 산출 값 자체는 로그에 남기지 않는다 (지표 로그 규칙). */
	private ApiException contractViolation(String what) {
		log.warn("[route] AI 응답 형태 위반 — 14502 수렴: {}", what);
		return new ApiException(RouteErrorCode.ROUTE_AI_UNAVAILABLE);
	}

	/** parse 요청 본문 — 사용자 식별 정보 필드가 구조적으로 없다 (NFR-SEC-09). */
	record ParseRequest(String text, Viewport viewport) {
	}

	/** parse 요청의 뷰포트 — 키는 AI 계약 표기(snake_case)다. */
	public record Viewport(
		@JsonProperty("min_lat") double minLat,
		@JsonProperty("min_lng") double minLng,
		@JsonProperty("max_lat") double maxLat,
		@JsonProperty("max_lng") double maxLng) {
	}

	/** explain 요청의 지점 하나 — kind 는 응답 DTO 다섯 값의 소문자, facts 는 지점당 1~5개 하한 1 (계약). */
	public record ExplainPoint(String name, String kind, List<String> facts) {
	}

	/** explain 응답 — reasons 는 points 와 같은 개수·순서, summary 는 동선 전체의 종합 이유다 (MSG-539). */
	public record ExplainResult(List<String> reasons, String summary) {
	}

	/**
	 * parse 해석 결과 — 전 필드가 빈 해석(null·빈 리스트)이어도 뷰포트 기준 추천이 성립한다 (FR-ROUTE-06).
	 * related 는 관련성 판정(FR-ROUTE-19) — 빈 해석과 무관은 다른 축이라, 확실히 무관할 때만 false 다.
	 */
	public record ParsedIntent(String region, Period period, List<String> interests, List<String> preferredOrder,
		boolean related) {

		/** 활성 필터에 쓰는 해석 기간 — 겹침 판정에 앞뒤 2일 여유를 두는 것은 소비처(후보 수집) 몫이다. */
		public record Period(LocalDate start, LocalDate end) {
		}
	}
}
