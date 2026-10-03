package com.msg.fillmap.global.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import com.msg.fillmap.MsgbeApplication;
import com.sun.net.httpserver.HttpServer;

/**
 * 기동 게이트 (MSG-608 D5, AC-03) — 워밍업이 끝나기 전에는 /actuator/health 가 200 을 주지 않는지.
 *
 * <p>@SpringBootTest 를 쓰지 않고 앱을 직접 띄운다. @SpringBootTest 는 테스트 스레드에서 컨텍스트를 올리므로
 * 러너(ApplicationReadyEvent 동기 리스너)가 도는 동안에는 테스트 메서드가 시작조차 못 해 "도는 동안"을 볼 수 없다.
 * 그래서 기동을 별도 스레드로 돌리고, 러너가 부르는 base-url 을 이 테스트의 스텁 서버로 돌려 스텁이 응답을
 * 래치로 붙잡는다. 러너에 테스트 훅을 뚫지 않고 진짜 러너가 진짜 호출에 묶인 상태를 만든다. 호출은 앱의 실제
 * API 로 가지 않으므로(대상 목록을 비운 것과 같은 효과) 공유 DB 에 데이터가 남지 않는다.
 *
 * <p>스텁은 러너의 읽기 타임아웃(5초) 안에 풀어야 한다 — 넘기면 호출이 실패로 세어지고 러너가 먼저 끝난다.
 */
class WarmupReadinessIntegrationTest {

	private static final long STARTUP_TIMEOUT_SECONDS = 120;

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final CountDownLatch release = new CountDownLatch(1);
	private HttpServer stub;
	private CompletableFuture<ConfigurableApplicationContext> startup;
	private ConfigurableApplicationContext context;

	/**
	 * 실패 경로(진입 대기 단언·health 예외·기동 타임아웃)에서도 백그라운드 앱을 반드시 닫는다 — 남으면 Tomcat·
	 * Hikari 커넥션·스케줄러가 JVM 종료까지 살아 공유 로컬 DB 커넥션 한도로 뒤 테스트를 깨뜨린다.
	 * 스텁을 먼저 풀어야 러너가 끝나 기동이 완료된다.
	 */
	@AfterEach
	void tearDown() {
		release.countDown();
		if (context != null) {
			context.close();
		} else if (startup != null) {
			startup.whenComplete((started, e) -> {
				if (started != null) {
					started.close();
				}
			});
		}
		if (stub != null) {
			stub.stop(0);
		}
	}

	// 검증: NFR-PERF-01, AC-608-03
	@Test
	void 워밍업이_도는_동안_health는_503이고_끝나면_200이다() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		stub.createContext("/", exchange -> {
			entered.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		stub.start();
		AtomicInteger appPort = new AtomicInteger();

		// iterations 1 · grid-user-id 없음 → 러너는 핫구역·미션 두 건만 스텁으로 보낸다.
		startup = CompletableFuture.supplyAsync(() -> start(
			true, appPort, "--fillmap.warmup.iterations=1",
			"--fillmap.warmup.base-url=http://localhost:" + stub.getAddress().getPort()));

		assertThat(entered.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
		HttpResponse<String> during = health(appPort.get());
		release.countDown();
		context = startup.get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		HttpResponse<String> after = health(appPort.get());

		assertThat(during.statusCode()).isEqualTo(503);
		assertThat(during.body()).contains("OUT_OF_SERVICE");
		assertThat(after.statusCode()).isEqualTo(200);
	}

	// 검증: NFR-PERF-01, AC-608-03
	@Test
	void 워밍업이_꺼져_있으면_기동_완료_즉시_health가_200이다() throws Exception {
		AtomicInteger appPort = new AtomicInteger();

		context = start(false, appPort);

		assertThat(health(appPort.get()).statusCode()).isEqualTo(200);
	}

	/**
	 * 값은 명령행 인자로 넘긴다 — SpringApplicationBuilder.properties 는 기본 프로퍼티(최하위 우선순위)라
	 * application.yml 의 server.port·테스트 yml 의 warmup.enabled=false 에 덮여 러너가 아예 안 돈다.
	 */
	private static ConfigurableApplicationContext start(boolean enabled, AtomicInteger appPort, String... args) {
		String[] allArgs = Stream.concat(Stream.of("--server.port=0", "--fillmap.warmup.enabled=" + enabled),
			Stream.of(args)).toArray(String[]::new);
		return new SpringApplicationBuilder(MsgbeApplication.class)
			.listeners(new ApplicationListener<ApplicationEvent>() {
				@Override
				public void onApplicationEvent(ApplicationEvent event) {
					if (event instanceof WebServerInitializedEvent initialized) {
						appPort.set(initialized.getWebServer().getPort());
					}
				}
			})
			.run(allArgs);
	}

	private HttpResponse<String> health(int port) throws IOException, InterruptedException {
		URI uri = URI.create("http://localhost:" + port + "/actuator/health");
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
