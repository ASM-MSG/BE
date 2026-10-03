package com.msg.fillmap.global.warmup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 기동 워밍업 러너 (MSG-608). 앱이 뜬 뒤 자기 자신에게 localhost HTTP 로 지도 홈 3종(핫구역 집계·미션 집계·
 * 격자 뷰포트 조회)을 대상별 N건씩 불러 JIT 컴파일을 배포 시점으로 당긴다. 데이터 캐시가 아니라 코드 경로를
 * 데우는 것이라 HTTP 로 부른다 — 시큐리티 필터·JWT 파싱·인자 바인딩·직렬화·압축까지 함께 데워진다(D2).
 *
 * <p>동기 리스너인 것이 곧 기동 게이트다. Boot 는 ApplicationReadyEvent 리스너가 전부 돌아온 뒤에야
 * readiness 를 ACCEPTING_TRAFFIC 으로 바꾸므로, 이 메서드가 도는 동안 /actuator/health 는 503 이다(D5).
 * 톰캣은 이미 떠 있고 main 스레드는 요청 스레드가 아니라 자기 호출이 교착하지 않는다.
 *
 * <p>실패 격리는 미션 스냅숏 부트 웜업(MSG-437)과 같은 원칙이다 — 워밍업은 성능 최적화지 기동 조건이
 * 아니다. 호출 하나의 실패는 세기만 하고 넘어가며 예외는 밖으로 나가지 않는다. 총 소요가 45초를 넘으면
 * 남은 호출을 버린다(healthcheck start_period 30s + retries 6 × 10s 산술, D1).
 */
@Slf4j
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class WarmupRunner {

	// healthcheck 값(docker-compose.app.yml)이 바뀌면 함께 바꾼다 — 프로퍼티로 빼지 않는다(D1).
	static final Duration TIME_LIMIT = Duration.ofSeconds(45);
	// 2 vCPU 에서 컴파일 스레드와 CPU 를 나눠 쓰는 처지라 더 늘려도 빨라지지 않는다(D1).
	static final int THREADS = 4;
	static final String THREAD_NAME_PREFIX = "warmup-";

	/*
	 * 실데이터가 있는 고정 뷰포트 (D3) — load-test/k6/cluster-benchmark.js 의 VIEWPORTS·CANARY_VIEWPORTS 그대로.
	 * 빈 응답을 데우면 이름 조회·격자 묶기 같은 실제 분기가 컴파일되지 않는다. 서울·부산 × SIGUNGU·SIDO,
	 * 미션 유형 EVENT·POPUP 교대. 격자 조회는 한 변 0.5도를 넘으면 거절하므로 도심 한 조각 둘만 쓴다.
	 */
	static final List<ZoomOut> ZOOM_OUTS = List.of(
		new ZoomOut("SIGUNGU", "EVENT", new Viewport(37.35, 126.78, 37.75, 127.18)),
		new ZoomOut("SIGUNGU", "POPUP", new Viewport(35.05, 128.95, 35.35, 129.25)),
		new ZoomOut("SIDO", "EVENT", new Viewport(37.00, 126.50, 38.00, 127.50)),
		new ZoomOut("SIDO", "POPUP", new Viewport(34.70, 128.50, 35.70, 129.50)),
		new ZoomOut("SIGUNGU", "POPUP", new Viewport(37.45, 126.90, 37.75, 127.20)),
		new ZoomOut("SIGUNGU", "EVENT", new Viewport(35.00, 128.85, 35.40, 129.25)),
		new ZoomOut("SIDO", "POPUP", new Viewport(36.50, 126.00, 38.50, 128.00)),
		new ZoomOut("SIDO", "EVENT", new Viewport(34.20, 128.00, 36.20, 130.00))
	);
	static final List<Viewport> GRID_VIEWPORTS = List.of(
		new Viewport(37.49, 127.01, 37.53, 127.07),
		new Viewport(35.14, 129.04, 35.18, 129.10)
	);

	private static final String HOTZONE_URI =
		"/api/hotzones/aggregation?unit={unit}&swLat={swLat}&swLng={swLng}&neLat={neLat}&neLng={neLng}";
	private static final String MISSION_URI = "/api/missions/aggregation?type={type}&unit={unit}"
		+ "&swLat={swLat}&swLng={swLng}&neLat={neLat}&neLng={neLng}";
	private static final String GRID_URI = "/api/grids?swLat={swLat}&swLng={swLng}&neLat={neLat}&neLng={neLng}"
		+ "&size=1000";

	private final WarmupProperties properties;
	private final RestClient warmupRestClient;
	private final TokenProvider tokenProvider;
	private final Clock clock;

	@Autowired
	public WarmupRunner(WarmupProperties properties, RestClient warmupRestClient, TokenProvider tokenProvider) {
		this(properties, warmupRestClient, tokenProvider, Clock.systemUTC());
	}

	@EventListener(ApplicationReadyEvent.class)
	public void warmUp() {
		if (!properties.enabled()) {
			return;
		}
		String gridAuthorization = gridAuthorization();
		Map<Target, AtomicInteger> calls = counters();
		Map<Target, AtomicInteger> failures = counters();
		AtomicBoolean timedOut = new AtomicBoolean(false);
		Instant start = clock.instant();

		ExecutorService pool = Executors.newFixedThreadPool(THREADS,
			Thread.ofPlatform().name(THREAD_NAME_PREFIX, 0).factory());
		// 대상을 번갈아 낸다 — 한 대상만 몰아 부르면 다른 대상의 컴파일이 뒤로 밀린다(D3).
		for (int i = 0; i < properties.iterations(); i++) {
			ZoomOut zoomOut = ZOOM_OUTS.get(i % ZOOM_OUTS.size());
			Viewport bounds = zoomOut.viewport();
			submit(pool, Target.HOTZONE, start, timedOut, calls, failures, () -> warmupRestClient.get()
				.uri(HOTZONE_URI, zoomOut.unit(), bounds.swLat(), bounds.swLng(), bounds.neLat(), bounds.neLng())
				.retrieve()
				.body(String.class));
			submit(pool, Target.MISSION, start, timedOut, calls, failures, () -> warmupRestClient.get()
				.uri(MISSION_URI, zoomOut.type(), zoomOut.unit(),
					bounds.swLat(), bounds.swLng(), bounds.neLat(), bounds.neLng())
				.retrieve()
				.body(String.class));
			if (gridAuthorization != null) {
				Viewport grid = GRID_VIEWPORTS.get(i % GRID_VIEWPORTS.size());
				submit(pool, Target.GRID, start, timedOut, calls, failures, () -> warmupRestClient.get()
					.uri(GRID_URI, grid.swLat(), grid.swLng(), grid.neLat(), grid.neLng())
					.header(HttpHeaders.AUTHORIZATION, gridAuthorization)
					.retrieve()
					.body(String.class));
			}
		}
		awaitOrAbandon(pool, timedOut);

		log.info("워밍업 완료: hotzone 호출 {}건 실패 {}건, mission 호출 {}건 실패 {}건, grid 호출 {}건 실패 {}건, "
				+ "총 {}ms, timedOut={}",
			calls.get(Target.HOTZONE), failures.get(Target.HOTZONE),
			calls.get(Target.MISSION), failures.get(Target.MISSION),
			calls.get(Target.GRID), failures.get(Target.GRID),
			Duration.between(start, clock.instant()).toMillis(), timedOut.get());
	}

	/**
	 * 격자 조회용 Authorization 헤더 (D4). 로그인 경로와 같은 발급기라 필터의 서명 검증까지 실제 코드가 데워진다.
	 * 토큰은 localhost 밖으로 나가지 않고 로그에 남기지 않는다.
	 */
	private String gridAuthorization() {
		if (properties.gridUserId() == null) {
			log.info("fillmap.warmup.grid-user-id 가 없어 격자 조회 워밍업을 건너뜁니다 (MSG-608)");
			return null;
		}
		try {
			return "Bearer " + tokenProvider.issueAccessToken(properties.gridUserId(), UserRole.USER);
		} catch (RuntimeException e) {
			// 리스너 밖으로 예외가 나가면 기동이 죽는다(D1) — 격자 대상만 건너뛴다. 토큰 값은 남기지 않는다.
			log.warn("격자 조회용 토큰 발급에 실패해 격자 조회 워밍업을 건너뜁니다 (MSG-608)", e);
			return null;
		}
	}

	private void submit(ExecutorService pool, Target target, Instant start, AtomicBoolean timedOut,
		Map<Target, AtomicInteger> calls, Map<Target, AtomicInteger> failures, Runnable call) {
		pool.execute(() -> {
			if (timedOut.get() || Duration.between(start, clock.instant()).compareTo(TIME_LIMIT) > 0) {
				timedOut.set(true);
				return;
			}
			try {
				call.run();
			} catch (RuntimeException e) {
				// 타임아웃·연결 거부·4xx·5xx 전부 — 세기만 하고 다음 호출로 간다(D1).
				failures.get(target).incrementAndGet();
			}
			calls.get(target).incrementAndGet();
		});
	}

	/**
	 * 호출마다 보는 시계 판정이 1차 상한이고, 이것은 응답이 안 오는 호출에 대비한 실시간 상한이다.
	 * 풀은 러너 안에서 만들고 여기서 반드시 닫는다(빈으로 두지 않는다, D1).
	 */
	private void awaitOrAbandon(ExecutorService pool, AtomicBoolean timedOut) {
		pool.shutdown();
		try {
			if (!pool.awaitTermination(TIME_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
				timedOut.set(true);
				pool.shutdownNow();
			}
		} catch (InterruptedException e) {
			timedOut.set(true);
			pool.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	private static Map<Target, AtomicInteger> counters() {
		Map<Target, AtomicInteger> counters = new EnumMap<>(Target.class);
		for (Target target : Target.values()) {
			counters.put(target, new AtomicInteger());
		}
		return counters;
	}

	private enum Target {
		HOTZONE, MISSION, GRID
	}

	record ZoomOut(String unit, String type, Viewport viewport) {
	}

	record Viewport(double swLat, double swLng, double neLat, double neLng) {
	}
}
