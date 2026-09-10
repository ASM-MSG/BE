package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import com.google.firebase.messaging.FirebaseMessaging;

/**
 * MSG-343 모듈 5 — 실제 /actuator/prometheus 응답으로 노출을 확인한다 (성공 기준 1·2, D4 실측).
 * 알림 계측(notification.outcome·backlog)은 fillmap.notification.enabled 게이트 뒤라 켜고 띄운다 —
 * EmbeddedKafka·FirebaseMessaging 목은 NotificationConsumerTest 와 같은 사정. 나머지 video 계측은
 * VideoProcessingMetrics 가 고정 태그 조합을 선등록하므로 증가 0회여도 이름이 보인다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"fillmap.notification.enabled=true",
	"fillmap.notification.relay.poll-interval-ms=3600000"
})
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DisplayName("MSG-343 Metric 노출 통합 — /actuator/prometheus 실측")
class MetricsExposureTest {

	@LocalServerPort
	private int port;

	@MockitoBean
	private FirebaseMessaging firebaseMessaging;

	private String scrape() {
		// Boot 4 는 TestRestTemplate 이 별도 모듈이라(신규 의존 금지) 기존 spring-web 의 RestTemplate 로 조회한다.
		return new RestTemplate().getForObject("http://localhost:" + port + "/actuator/prometheus", String.class);
	}

	@Test
	void prometheus_엔드포인트에_새_Metric_이름이_전부_보인다() {
		assertThat(scrape())
			.contains("video_processing_outcome_total")
			.contains("video_processing_duration_seconds")
			.contains("video_encoding_task_total")
			.contains("video_encoding_job_total")
			.contains("video_encoding_queue")
			.contains("video_ai_job_total")
			.contains("notification_outcome_total")
			.contains("notification_backlog");
	}

	@Test
	void 처리_시간_히스토그램에_30초_버킷이_있다() {
		// yml SLO 버킷 적용 확인 — 30초 경계가 "30초 이내 완료 비율" 판정선이라 필수다 (성공 기준 2).
		assertThat(scrape())
			.containsPattern("video_processing_duration_seconds_bucket\\{[^}]*le=\"30.0\"");
	}

	@Test
	void encodingExecutor_큐_Metric이_노출된다() {
		// D4 실측 — Boot 자동 바인딩(ExecutorServiceMetrics)이 노출하면 그대로 재사용, 미노출이면 수동 바인딩 추가.
		assertThat(scrape())
			.containsPattern("(?m)^executor_queued[^\\n]*name=\"encodingExecutor\"");
	}
}
