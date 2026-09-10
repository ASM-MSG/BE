package com.msg.fillmap.video.service;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;

import com.msg.fillmap.video.config.AiProperties;

/**
 * AI Highlight-Blur 서버(FastAPI) 어댑터 (MSG-149). webmvc 내장 RestClient 만 쓴다 — 신규 HTTP 의존성 없음.
 * 계약 정본: FillMap-AI/README "API (BE ↔ AI 계약)". enabled 일 때만 빈으로 뜬다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled")
public class AiClient {

	// 선분석 전용 타임아웃 (MSG-351 D-5, 교차 리뷰 P1-B 로 60→120초). 폴러용 builder 와 별도 전용 값이다.
	// JdkClientHttpRequestFactory 는 readTimeout 을 HttpRequest.timeout() 으로 걸어 idle read 가 아니라
	// 교환 전체(본문 전송+AI 처리+응답 수신)의 단일 시한이 된다 — 2GiB 원본 전송 시간이 시한 안으로
	// 들어오므로 D-5 의 60초를 늘렸다. 산정: EC2 실측 174초 원본 처리 31.87초 × 동시 허용 2건 경합 여유
	// 3배 ≈ 96초 + 내부망 2GiB 전송 여유 = 120초. env 튜닝 수요가 없어 AiConfig 관례대로 상수다.
	private static final Duration HIGHLIGHT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration HIGHLIGHT_READ_TIMEOUT = Duration.ofSeconds(120);

	private final RestClient restClient;
	private final RestClient highlightRestClient;

	@Autowired
	public AiClient(RestClient.Builder builder, AiProperties properties) {
		this(builder, RestClient.builder().requestFactory(highlightRequestFactory()), properties);
	}

	/** 테스트 진입점 — 선분석 builder 에 MockRestServiceServer 를 따로 바인딩하기 위한 것 (FfmpegRunner 선례). */
	AiClient(RestClient.Builder builder, RestClient.Builder highlightBuilder, AiProperties properties) {
		this.restClient = builder.baseUrl(properties.baseUrl()).build();
		this.highlightRestClient = highlightBuilder.baseUrl(properties.baseUrl()).build();
	}

	/**
	 * 선분석 전용 요청 팩토리 — java.net.http 기반 (교차 리뷰 P1-B). SimpleClientHttpRequestFactory
	 * (HttpURLConnection)의 read timeout 은 응답 read 만 묶고 요청 본문 쓰기(최대 2GiB 업로드)는 시한이
	 * 없어서, AI 서버가 본문을 안 읽으면 스레드가 무기한 붙잡힌다. 테스트 검증점 겸용 package-private.
	 */
	static JdkClientHttpRequestFactory highlightRequestFactory() {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(HIGHLIGHT_CONNECT_TIMEOUT)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(HIGHLIGHT_READ_TIMEOUT);
		return factory;
	}

	/** 인코딩본을 multipart file 로 제출한다. 202 {job_id} 의 job_id 를 반환한다. */
	public String submit(byte[] encoded) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(encoded) {
			@Override
			public String getFilename() {
				return "encoded.mp4";
			}
		});

		JsonNode response = restClient.post()
			.uri("/jobs")
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(body)
			.retrieve()
			.body(JsonNode.class);

		return response.path("job_id").asString();
	}

	/**
	 * 확정 전 원본의 하이라이트 선분석 (MSG-351, 동기 계약 MSG-353 POST /highlights). 전송은 FileSystemResource —
	 * 선분석 원본은 GB급일 수 있어 submit 의 ByteArrayResource 처럼 힙에 올리면 t3.small 이 죽는다 (D-5).
	 * 422(원본 불량)만 구분 예외로, 나머지 실패(연결·타임아웃·5xx·파싱 불능)는 업스트림 예외 하나로 수렴한다.
	 */
	public List<List<Double>> analyzeHighlights(Path source) {
		JsonNode response;
		try {
			MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
			body.add("file", new FileSystemResource(source));
			response = highlightRestClient.post()
				.uri("/highlights")
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body)
				.retrieve()
				.body(JsonNode.class);
		} catch (HttpClientErrorException e) {
			// 상태값 비교 — 중첩 상태별 예외(UnprocessableEntity 등)는 Spring 7 에서 deprecated 다.
			if (e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT)) {
				throw new HighlightSourceRejectedException(e);
			}
			throw new HighlightUpstreamException(e);
		} catch (RestClientException e) {
			throw new HighlightUpstreamException(e);
		}
		List<List<Double>> highlights;
		try {
			highlights = response == null ? null : highlights(response);
		} catch (RuntimeException e) {
			throw new HighlightUpstreamException(e);   // 구간 요소가 배열이 아닌 malformed 응답
		}
		if (highlights == null) {
			throw new HighlightUpstreamException("highlights 필드 없는 선분석 응답");
		}
		return highlights;
	}

	/** job 상태를 폴링한다. 404(잡 유실)는 notFound 로 매핑한다. */
	public AiJobResult poll(String jobId) {
		try {
			JsonNode response = restClient.get()
				.uri("/jobs/{id}", jobId)
				.retrieve()
				.body(JsonNode.class);
			return new AiJobResult(parseStatus(response.path("status").asString()), highlights(response), false,
				precheck(response));
		} catch (HttpClientErrorException.NotFound e) {
			return new AiJobResult(null, null, true);
		}
	}

	/**
	 * status 문자열을 안전하게 매핑한다. 미지/누락 값(배포 스큐로 인한 일시 garbage 포함)은 UNKNOWN 으로 —
	 * valueOf 예외가 외곽 catch 로 새면 영구 BLURRING 이 되므로, 폴러가 타임아웃 경로로 수렴하게 한다 (P2-b).
	 */
	private AiJobStatus parseStatus(String raw) {
		try {
			return AiJobStatus.valueOf(raw);
		} catch (IllegalArgumentException e) {
			log.warn("AI status 해석 불가 — UNKNOWN 으로 매핑: raw={}", raw);
			return AiJobStatus.UNKNOWN;
		}
	}

	/** 완료본(블러 mp4) 바이트를 받는다. 완료 전 409 는 미완료 신호로 null 을 반환한다. */
	public byte[] downloadBlurred(String jobId) {
		try {
			return restClient.get()
				.uri("/jobs/{id}/video", jobId)
				.retrieve()
				.body(byte[].class);
		} catch (HttpClientErrorException.Conflict e) {
			return null;
		}
	}

	/**
	 * precheck 판정 (MSG-284 FR-4, MSG-286). 필드 부재(AI 구버전)·null(판정 전)은 null — 계약상 구분할 이유가
	 * 없다 (FR-3). passed 가 boolean 이 아니면(malformed) null — asBoolean 기본값 false 로 탈락 오판하면 정상
	 * 영상이 즉시 실패하므로, 판정 불능은 기존 경로로 보낸다 (parseStatus 의 UNKNOWN 방어와 같은 P2-b 계열).
	 */
	private Precheck precheck(JsonNode response) {
		JsonNode node = response.path("precheck");
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		JsonNode passed = node.path("passed");
		if (!passed.isBoolean()) {
			log.warn("AI precheck.passed 해석 불가 — 판정 전으로 취급: raw={}", passed);
			return null;
		}
		JsonNode reason = node.path("reason");
		return new Precheck(passed.asBoolean(),
			reason.isMissingNode() || reason.isNull() ? null : reason.asString());
	}

	/**
	 * highlights = [[시작초, 끝초], ...] 최대 3구간, 소수점 2자리 (MSG-145). 없으면 null.
	 * 형태 위반(비배열 노드·숫자 2개 배열이 아닌 구간)은 예외로 올린다 (MSG-351 교차 리뷰 P2-1) —
	 * 비배열을 그대로 순회하면 빈 배열, 즉 "추천 없음" 유효 응답으로 조용히 둔갑하기 때문이다.
	 * 선분석은 catch 가 3502 로 수렴시키고, 폴러는 poll 실패 경로(타임아웃 수렴)로 흡수한다.
	 */
	private List<List<Double>> highlights(JsonNode response) {
		JsonNode node = response.path("highlights");
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (!node.isArray()) {
			throw new IllegalStateException("highlights 가 배열이 아닌 AI 응답: " + node.getNodeType());
		}
		List<List<Double>> highlights = new ArrayList<>();
		for (JsonNode span : node) {
			if (!span.isArray() || span.size() != 2 || !span.get(0).isNumber() || !span.get(1).isNumber()) {
				throw new IllegalStateException("구간이 [시작초, 끝초] 숫자 쌍이 아닌 AI 응답: " + span);
			}
			highlights.add(List.of(span.get(0).asDouble(), span.get(1).asDouble()));
		}
		return highlights;
	}

	/** AI job 상태 (계약: QUEUED → PROCESSING → DONE | FAILED). UNKNOWN=해석 불가 status 방어 매핑(P2-b). */
	public enum AiJobStatus {
		QUEUED,
		PROCESSING,
		DONE,
		FAILED,
		UNKNOWN
	}

	/** 프리체크 판정 (MSG-284 FR-4). reason 은 계약 원문 그대로 — 코드 추출은 소비 지점(폴러) 몫. */
	public record Precheck(boolean passed, String reason) {
	}

	/** 선분석 원본 불량(AI 422) — 재시도 무의미. 소비처가 3426 으로 매핑한다 (MSG-351). */
	public static class HighlightSourceRejectedException extends RuntimeException {

		public HighlightSourceRejectedException(Throwable cause) {
			super(cause);
		}
	}

	/** 선분석 업스트림 실패(연결·타임아웃·5xx·파싱 불능) 단일 수렴 — 소비처가 3502 로 매핑한다 (MSG-351). */
	public static class HighlightUpstreamException extends RuntimeException {

		public HighlightUpstreamException(String message) {
			super(message);
		}

		public HighlightUpstreamException(Throwable cause) {
			super(cause);
		}
	}

	/** 폴링 결과 — status(유실 시 null)·highlights·notFound(404)·precheck(부재/판정 전 null, MSG-286). */
	public record AiJobResult(AiJobStatus status, List<List<Double>> highlights, boolean notFound,
		Precheck precheck) {

		/** precheck 부재 호환 생성자 — 기존 생성부(테스트 다수)와 404 경로가 그대로 쓴다. */
		public AiJobResult(AiJobStatus status, List<List<Double>> highlights, boolean notFound) {
			this(status, highlights, notFound, null);
		}
	}
}
