package com.msg.fillmap.hotzone.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 핫스코어 집계 구현 (MSG-183). 키: {@code hotzone:{bucketId}} Sorted Set, member=gridId.
 * bucketId = epochSeconds / 21600 — UTC 6h 고정 버킷 (D2). TTL 54h 는 청소 전용이며
 * 48h 윈도우 판정은 조회(MSG-184) 몫 (D4). 실패는 삼키고 warn 로깅만 한다 (D6, FR-6).
 *
 * Redis 호출은 자체 단일 데몬 스레드로 분리한다 — afterCommit 콜백은 요청 스레드에서 돌므로
 * 동기 호출이면 Redis 장애 시 업로드 응답이 타임아웃만큼 지연된다(D6 명분 2). 큐 포화 시
 * 거부는 폐기하고 warn 만 남긴다 — 신호 유실 허용(D4)과 일관.
 */
@Slf4j
@Service
public class HotScoreCommandServiceImpl implements HotScoreCommandService {

	private static final String KEY_PREFIX = "hotzone:";
	private static final long BUCKET_SECONDS = 21600L;
	private static final long BUCKET_TTL_SECONDS = Duration.ofHours(54).toSeconds();
	private static final int QUEUE_CAPACITY = 10_000;
	private static final long DRAIN_TIMEOUT_SECONDS = 5;

	/** ZINCRBY+EXPIRE 원자 실행 — 분리 2명령이면 ZINCRBY 후 단절 시 TTL 없는 키가 잔존한다. */
	private static final RedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
		"redis.call('ZINCRBY', KEYS[1], 1, ARGV[1]) redis.call('EXPIRE', KEYS[1], ARGV[2]) return 1", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;
	private final Executor executor;

	@Autowired
	public HotScoreCommandServiceImpl(StringRedisTemplate redisTemplate) {
		this(redisTemplate, Clock.systemUTC(), defaultExecutor());
	}

	/** 결정적 테스트용 — 고정 Clock(버킷 경계)·same-thread Executor 주입 (MSG-183 §테스트 환경). */
	public HotScoreCommandServiceImpl(StringRedisTemplate redisTemplate, Clock clock, Executor executor) {
		this.redisTemplate = redisTemplate;
		this.clock = clock;
		this.executor = executor;
	}

	@Override
	public void recordUpload(String gridId) {
		try {
			// 버킷 키는 호출(커밋) 시각에 확정 — 큐가 밀려 워커가 6h 경계를 넘겨 실행돼도 이벤트 시각 버킷에 기록
			String key = KEY_PREFIX + clock.instant().getEpochSecond() / BUCKET_SECONDS;
			executor.execute(() -> increment(key, gridId));
		} catch (Exception e) {
			// 큐 포화(RejectedExecutionException) 포함 — 폐기하고 warn 만 남긴다 (FR-6)
			log.warn("핫스코어 증분 실패 — 신호 1건 유실 허용: gridId={}", gridId, e);
		}
	}

	/** 워커 스레드 실행 — clock 을 읽지 않는다(버킷은 호출 시각에 이미 확정). */
	private void increment(String key, String gridId) {
		try {
			redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), gridId, String.valueOf(BUCKET_TTL_SECONDS));
		} catch (Exception e) {
			log.warn("핫스코어 증분 실패 — 신호 1건 유실 허용: gridId={}", gridId, e);   // FR-6
		}
	}

	@PreDestroy
	void shutdown() {
		if (!(executor instanceof ExecutorService executorService)) {
			return;
		}
		// 수락된 신호는 커밋된 업로드다 — 일상 배포에선 드레인하고, 타임아웃·인터럽트에서만 유실 허용(D4)
		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				log.warn("핫스코어 드레인 타임아웃 — 잔여 신호 {}건 유실 허용", executorService.shutdownNow().size());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("핫스코어 드레인 인터럽트 — 잔여 신호 {}건 유실 허용", executorService.shutdownNow().size());
		}
	}

	private static ThreadPoolExecutor defaultExecutor() {
		// ponytail: 단일 스레드 직렬 처리 — O(1) ZINCRBY 라 업로드 TPS 수준에선 충분, 밀리면 스레드 수 증설
		return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(QUEUE_CAPACITY),
			runnable -> {
				Thread thread = new Thread(runnable, "hotzone-score");
				thread.setDaemon(true);
				return thread;
			});
	}
}
