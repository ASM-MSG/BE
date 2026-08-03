package com.msg.fillmap.video.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

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

	private final RestClient restClient;

	public AiClient(RestClient.Builder builder, AiProperties properties) {
		this.restClient = builder.baseUrl(properties.baseUrl()).build();
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

	/** highlights = [[시작초, 끝초], ...] 최대 3구간, 소수점 2자리 (MSG-145). 없으면 null. */
	private List<List<Double>> highlights(JsonNode response) {
		JsonNode node = response.path("highlights");
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		List<List<Double>> highlights = new ArrayList<>();
		for (JsonNode span : node) {
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

	/** 폴링 결과 — status(유실 시 null)·highlights·notFound(404)·precheck(부재/판정 전 null, MSG-286). */
	public record AiJobResult(AiJobStatus status, List<List<Double>> highlights, boolean notFound,
		Precheck precheck) {

		/** precheck 부재 호환 생성자 — 기존 생성부(테스트 다수)와 404 경로가 그대로 쓴다. */
		public AiJobResult(AiJobStatus status, List<List<Double>> highlights, boolean notFound) {
			this(status, highlights, notFound, null);
		}
	}
}
