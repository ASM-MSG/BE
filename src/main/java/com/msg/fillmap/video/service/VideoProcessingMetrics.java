package com.msg.fillmap.video.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 영상 처리 Metric 집계 지점 (MSG-343). 태그 조합은 스펙 표의 고정 집합만 생성 시 선등록한다 —
 * 가변값 태그 금지(D1)를 구조로 강제하고, /actuator/prometheus 에 0값으로도 항상 노출되게 한다.
 * 미등록 값 증가 요청은 조용히 무시한다 — 계측이 본 처리를 깨뜨리지 않는다.
 *
 * 처리 시간은 DB 작업의 enqueued_at부터 종결 UTC 시각까지 기록한다 (MSG-494 D7).
 * 실행 노드가 달라지거나 재시작돼도 시작점이 사라지지 않는다.
 */
@Component
public class VideoProcessingMetrics {

	public static final String PATH_ENCODING = "encoding";
	public static final String PATH_AI = "ai";

	public static final String TASK_COMPLETED = "completed";
	public static final String TASK_FAILED_OVER_DURATION = "failed_over_duration";
	public static final String TASK_FAILED_ERROR = "failed_error";

	public static final String AI_SUBMITTED = "submitted";
	public static final String AI_DONE = "done";
	public static final String AI_FAILED = "failed";
	public static final String AI_PRECHECK_REJECTED = "precheck_rejected";
	public static final String AI_TIMEOUT = "timeout";
	public static final String AI_JOB_LOST = "job_lost";

	private final Map<String, Counter> outcomeCounters = new HashMap<>();
	private final Map<String, Timer> durationTimers = new HashMap<>();
	private final Map<String, Counter> encodingTaskCounters = new HashMap<>();
	private final Map<String, Counter> aiJobCounters = new HashMap<>();
	private final Clock clock;

	@Autowired
	public VideoProcessingMetrics(MeterRegistry registry) {
		this(registry, Clock.systemUTC());
	}

	VideoProcessingMetrics(MeterRegistry registry, Clock clock) {
		this.clock = clock;
		for (String outcome : List.of("ready", "failed")) {
			for (String path : List.of(PATH_ENCODING, PATH_AI)) {
				outcomeCounters.put(outcome + "|" + path, Counter.builder("video.processing.outcome")
					.tag("outcome", outcome).tag("path", path).register(registry));
				durationTimers.put(outcome + "|" + path, Timer.builder("video.processing.duration")
					.tag("outcome", outcome).tag("path", path).register(registry));
			}
		}
		for (String result : List.of(TASK_COMPLETED, TASK_FAILED_OVER_DURATION, TASK_FAILED_ERROR)) {
			encodingTaskCounters.put(result,
				Counter.builder("video.encoding.task").tag("result", result).register(registry));
		}
		for (String event : List.of(AI_SUBMITTED, AI_DONE, AI_FAILED, AI_PRECHECK_REJECTED, AI_TIMEOUT, AI_JOB_LOST)) {
			aiJobCounters.put(event, Counter.builder("video.ai.job").tag("event", event).register(registry));
		}
	}

	/**
	 * 종결 전이 계측 (D5) — Counter 는 무조건 +1, Timer 는 DB 시작점이 있을 때만 기록한다.
	 * VideoStatusWriter 의 전이 적용 분기에서만 호출된다(스테일 skip 은 호출 자체가 없다).
	 */
	public void recordOutcome(LocalDateTime enqueuedAt, boolean ready, String path) {
		String key = (ready ? "ready" : "failed") + "|" + path;
		Counter counter = outcomeCounters.get(key);
		if (counter != null) {
			counter.increment();
		}
		Timer timer = durationTimers.get(key);
		if (enqueuedAt != null && timer != null) {
			Duration duration = Duration.between(enqueuedAt, LocalDateTime.now(clock));
			if (!duration.isNegative()) {
				timer.record(duration);
			}
		}
	}

	/** 인코딩 태스크 결과 계측 — result 는 TASK_* 고정값만. */
	public void countEncodingTask(String result) {
		increment(encodingTaskCounters, result);
	}

	/** AI 잡 이벤트 계측 — event 는 AI_* 고정값만. */
	public void countAiJob(String event) {
		increment(aiJobCounters, event);
	}

	private void increment(Map<String, Counter> counters, String key) {
		Counter counter = counters.get(key);
		if (counter != null) {
			counter.increment();
		}
	}
}
