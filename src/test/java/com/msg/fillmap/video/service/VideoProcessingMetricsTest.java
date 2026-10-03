package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VideoProcessingMetrics — 영상 처리 종결 계측")
class VideoProcessingMetricsTest {

	private SimpleMeterRegistry registry;
	private VideoProcessingMetrics metrics;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		Clock clock = Clock.fixed(Instant.parse("2026-08-27T06:00:05Z"), ZoneOffset.UTC);
		metrics = new VideoProcessingMetrics(registry, clock);
	}

	@Test
	void DB_등록시각부터_종결시각까지_timer를_기록한다() {
		LocalDateTime enqueuedAt = LocalDateTime.of(2026, 8, 27, 6, 0, 0);

		metrics.recordOutcome(enqueuedAt, true, VideoProcessingMetrics.PATH_ENCODING);

		assertThat(outcomeCount("ready", "encoding")).isEqualTo(1.0);
		assertThat(durationTimer("ready", "encoding").count()).isEqualTo(1L);
		assertThat(durationTimer("ready", "encoding").totalTime(TimeUnit.SECONDS)).isEqualTo(5.0);
	}

	@Test
	void 등록시각이_없어도_counter는_기록하고_timer는_생략한다() {
		metrics.recordOutcome(null, false, VideoProcessingMetrics.PATH_AI);

		assertThat(outcomeCount("failed", "ai")).isEqualTo(1.0);
		assertThat(durationTimer("failed", "ai").count()).isZero();
	}

	@Test
	void 미래_등록시각은_음수_timer를_기록하지_않는다() {
		LocalDateTime future = LocalDateTime.of(2026, 8, 27, 6, 0, 6);

		metrics.recordOutcome(future, true, VideoProcessingMetrics.PATH_ENCODING);

		assertThat(outcomeCount("ready", "encoding")).isEqualTo(1.0);
		assertThat(durationTimer("ready", "encoding").count()).isZero();
	}

	// ── ffmpeg 경로 계측 (MSG-615 D5) ──

	// 검증: AC-615-07
	@Test
	void 인코딩_경로_counter는_세_값이_0으로_선등록된다() {
		for (String path : List.of("remux", "encode", "fallback")) {
			assertThat(registry.get("video.encoding.path").tag("path", path).counter().count())
				.as("path=%s 는 증가 전에도 0 값으로 노출돼야 한다 (AC-615-07)", path)
				.isZero();
		}
	}

	@Test
	void 인코딩_경로_counter는_고정값만_증가하고_미등록값은_무시한다() {
		metrics.countEncodingPath(VideoProcessingMetrics.ENCODING_PATH_REMUX);
		metrics.countEncodingPath(VideoProcessingMetrics.ENCODING_PATH_FALLBACK);
		metrics.countEncodingPath("transcode");   // 미등록 — 가변값 태그 금지(D1)라 조용히 무시

		assertThat(registry.get("video.encoding.path").tag("path", "remux").counter().count()).isEqualTo(1.0);
		assertThat(registry.get("video.encoding.path").tag("path", "fallback").counter().count()).isEqualTo(1.0);
		assertThat(registry.get("video.encoding.path").tag("path", "encode").counter().count()).isZero();
		assertThat(registry.find("video.encoding.path").tag("path", "transcode").counter()).isNull();
	}

	private double outcomeCount(String outcome, String path) {
		return registry.get("video.processing.outcome")
			.tags("outcome", outcome, "path", path)
			.counter()
			.count();
	}

	private Timer durationTimer(String outcome, String path) {
		return registry.get("video.processing.duration")
			.tags("outcome", outcome, "path", path)
			.timer();
	}
}
