package com.msg.fillmap.global.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;

class WarmupRunnerTest {

	private static final String BASE_URL = "http://localhost:8080";
	private static final Long GRID_USER_ID = 7L;
	private static final String TOKEN = "warmup-token";

	private final TokenProvider tokenProvider = mock(TokenProvider.class);
	private final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T00:00:00Z"));
	private MockRestServiceServer server;
	private RestClient restClient;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
		restClient = builder.build();
		given(tokenProvider.issueAccessToken(eq(GRID_USER_ID), eq(UserRole.USER))).willReturn(TOKEN);
		logs = new ListAppender<>();
		logs.start();
		((Logger) LoggerFactory.getLogger(WarmupRunner.class)).addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(WarmupRunner.class)).detachAppender(logs);
	}

	// 검증: NFR-PERF-01, AC-608-01
	@Test
	void 워밍업이_꺼져_있으면_아무_호출도_하지_않고_로그도_남기지_않는다() {
		runner(false, 16, GRID_USER_ID).warmUp();

		server.verify();
		assertThat(logs.list).isEmpty();
	}

	// 검증: NFR-PERF-01, AC-608-01
	@Test
	void 켜져_있으면_핫구역_미션_격자를_대상별_N건씩_뷰포트를_순환하며_부른다() {
		int iterations = 16;
		for (WarmupRunner.ZoomOut zoomOut : WarmupRunner.ZOOM_OUTS) {
			server.expect(times(iterations / 8), requestTo(hotzoneUrl(zoomOut))).andRespond(withSuccess());
			server.expect(times(iterations / 8), requestTo(missionUrl(zoomOut))).andRespond(withSuccess());
		}
		for (WarmupRunner.Viewport viewport : WarmupRunner.GRID_VIEWPORTS) {
			server.expect(times(iterations / 2), requestTo(gridUrl(viewport))).andRespond(withSuccess());
		}

		runner(true, iterations, GRID_USER_ID).warmUp();

		server.verify();
	}

	// 검증: NFR-PERF-01, AC-608-01
	@Test
	void 격자_사용자_id가_없으면_격자_조회만_건너뛰고_집계_2종은_부른다() {
		int iterations = 8;
		for (WarmupRunner.ZoomOut zoomOut : WarmupRunner.ZOOM_OUTS) {
			server.expect(times(1), requestTo(hotzoneUrl(zoomOut))).andRespond(withSuccess());
			server.expect(times(1), requestTo(missionUrl(zoomOut))).andRespond(withSuccess());
		}

		runner(true, iterations, null).warmUp();

		server.verify();
		assertThat(messages()).anyMatch(message -> message.contains("격자 조회 워밍업을 건너뜁니다"));
	}

	// 검증: NFR-PERF-01, AC-608-01
	@Test
	void 격자_조회_요청에는_발급한_토큰이_Bearer로_붙는다() {
		server.expect(manyTimes(), requestTo(Matchers.startsWith(BASE_URL + "/api/hotzones/")))
			.andRespond(withSuccess());
		server.expect(manyTimes(), requestTo(Matchers.startsWith(BASE_URL + "/api/missions/")))
			.andRespond(withSuccess());
		server.expect(times(2), requestTo(Matchers.startsWith(BASE_URL + "/api/grids")))
			.andExpect(header("Authorization", "Bearer " + TOKEN))
			.andRespond(withSuccess());

		runner(true, 2, GRID_USER_ID).warmUp();

		server.verify();
	}

	// 검증: NFR-PERF-01, AC-608-02
	@Test
	void 한_대상이_500을_돌려줘도_나머지_호출은_계속되고_예외가_밖으로_나가지_않는다() {
		server.expect(times(8), requestTo(Matchers.startsWith(BASE_URL + "/api/hotzones/")))
			.andRespond(withServerError());
		server.expect(times(8), requestTo(Matchers.startsWith(BASE_URL + "/api/missions/")))
			.andRespond(withSuccess());
		server.expect(times(8), requestTo(Matchers.startsWith(BASE_URL + "/api/grids")))
			.andRespond(withSuccess());

		assertThatCode(() -> runner(true, 8, GRID_USER_ID).warmUp()).doesNotThrowAnyException();

		server.verify();
		assertThat(completionLog()).contains("hotzone 호출 8건 실패 8건", "mission 호출 8건 실패 0건");
	}

	// 검증: NFR-PERF-01, AC-608-02
	@Test
	void 한_대상이_연결_거부여도_실패로_세고_다음_호출로_간다() {
		server.expect(times(8), requestTo(Matchers.startsWith(BASE_URL + "/api/hotzones/")))
			.andRespond(withSuccess());
		server.expect(times(8), requestTo(Matchers.startsWith(BASE_URL + "/api/missions/")))
			.andRespond(withSuccess());
		server.expect(times(8), requestTo(Matchers.startsWith(BASE_URL + "/api/grids")))
			.andRespond(withException(new ConnectException("Connection refused")));

		assertThatCode(() -> runner(true, 8, GRID_USER_ID).warmUp()).doesNotThrowAnyException();

		server.verify();
		assertThat(completionLog()).contains("grid 호출 8건 실패 8건", "hotzone 호출 8건 실패 0건");
	}

	// 검증: NFR-PERF-01, AC-608-02
	@Test
	void 토큰_발급이_예외를_던져도_집계_2종은_불리고_예외가_밖으로_나가지_않는다() {
		given(tokenProvider.issueAccessToken(eq(GRID_USER_ID), eq(UserRole.USER)))
			.willThrow(new IllegalStateException("서명 키 오류"));
		for (WarmupRunner.ZoomOut zoomOut : WarmupRunner.ZOOM_OUTS) {
			server.expect(times(1), requestTo(hotzoneUrl(zoomOut))).andRespond(withSuccess());
			server.expect(times(1), requestTo(missionUrl(zoomOut))).andRespond(withSuccess());
		}

		assertThatCode(() -> runner(true, 8, GRID_USER_ID).warmUp()).doesNotThrowAnyException();

		server.verify();
		assertThat(completionLog()).contains("grid 호출 0건 실패 0건");
	}

	// 검증: NFR-PERF-01, AC-608-02
	@Test
	void 총_소요가_45초를_넘으면_남은_호출을_버리고_timedOut과_완료_건수를_로그에_남긴다() {
		// 응답 한 건마다 시계가 1초 간다 — 45건 남짓에서 상한에 닿는다.
		server.expect(manyTimes(), requestTo(Matchers.startsWith(BASE_URL)))
			.andRespond(request -> {
				clock.advance(Duration.ofSeconds(1));
				return withSuccess().createResponse(request);
			});

		runner(true, 100, GRID_USER_ID).warmUp();

		String log = completionLog();
		assertThat(log).contains("timedOut=true");
		int completed = count(log, "hotzone") + count(log, "mission") + count(log, "grid");
		assertThat(completed).isBetween(45, 60);
	}

	// 검증: NFR-PERF-01, AC-608-01
	@Test
	void 완료_로그_한_줄에_대상별_호출_수와_실패_수와_총_소요가_있다() {
		server.expect(manyTimes(), requestTo(Matchers.startsWith(BASE_URL)))
			.andRespond(request -> {
				clock.advance(Duration.ofMillis(10));
				return withSuccess().createResponse(request);
			});

		runner(true, 8, GRID_USER_ID).warmUp();

		assertThat(completionLog()).isEqualTo("워밍업 완료: hotzone 호출 8건 실패 0건, mission 호출 8건 실패 0건, "
			+ "grid 호출 8건 실패 0건, 총 240ms, timedOut=false");
	}

	// 검증: NFR-PERF-01, AC-608-01
	@Test
	void 러너가_끝나면_워커_스레드_풀이_닫혀_있다() throws InterruptedException {
		server.expect(manyTimes(), requestTo(Matchers.startsWith(BASE_URL))).andRespond(withSuccess());

		runner(true, 8, GRID_USER_ID).warmUp();

		List<Thread> workers = Thread.getAllStackTraces().keySet().stream()
			.filter(thread -> thread.getName().startsWith(WarmupRunner.THREAD_NAME_PREFIX))
			.toList();
		for (Thread worker : workers) {
			// 풀이 닫히지 않았으면 유휴 워커가 큐에서 영원히 대기해 join 이 풀리지 않는다.
			worker.join(1000);
			assertThat(worker.isAlive()).isFalse();
		}
	}

	private WarmupRunner runner(boolean enabled, int iterations, Long gridUserId) {
		WarmupProperties properties = new WarmupProperties(enabled, iterations, gridUserId, BASE_URL);
		return new WarmupRunner(properties, restClient, tokenProvider, clock);
	}

	private List<String> messages() {
		return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private String completionLog() {
		List<String> completions = messages().stream().filter(message -> message.startsWith("워밍업 완료")).toList();
		assertThat(completions).hasSize(1);
		return completions.get(0);
	}

	private static int count(String log, String target) {
		String marker = target + " 호출 ";
		int start = log.indexOf(marker) + marker.length();
		return Integer.parseInt(log.substring(start, log.indexOf('건', start)));
	}

	private static String hotzoneUrl(WarmupRunner.ZoomOut zoomOut) {
		return BASE_URL + "/api/hotzones/aggregation?unit=" + zoomOut.unit() + "&" + bbox(zoomOut.viewport());
	}

	private static String missionUrl(WarmupRunner.ZoomOut zoomOut) {
		return BASE_URL + "/api/missions/aggregation?type=" + zoomOut.type() + "&unit=" + zoomOut.unit() + "&"
			+ bbox(zoomOut.viewport());
	}

	private static String gridUrl(WarmupRunner.Viewport viewport) {
		return BASE_URL + "/api/grids?" + bbox(viewport) + "&size=1000";
	}

	private static String bbox(WarmupRunner.Viewport viewport) {
		return "swLat=" + viewport.swLat() + "&swLng=" + viewport.swLng() + "&neLat=" + viewport.neLat()
			+ "&neLng=" + viewport.neLng();
	}

	private static final class MutableClock extends Clock {

		private final AtomicReference<Instant> now;

		MutableClock(Instant start) {
			this.now = new AtomicReference<>(start);
		}

		void advance(Duration duration) {
			now.updateAndGet(instant -> instant.plus(duration));
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	}
}
