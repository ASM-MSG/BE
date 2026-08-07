package com.msg.fillmap.hotzone.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
 * 거부는 폐기하고 건수만 주기 요약한다 — 신호 유실 허용(D4)과 일관.
 */
@Slf4j
@Service
public class HotScoreCommandServiceImpl implements HotScoreCommandService {

	private static final String KEY_PREFIX = "hotzone:";
	private static final long BUCKET_SECONDS = 21600L;
	private static final long BUCKET_TTL_SECONDS = Duration.ofHours(54).toSeconds();
	private static final int QUEUE_CAPACITY = 10_000;
	private static final long DRAIN_TIMEOUT_SECONDS = 5;
	private static final long DISCARD_LOG_INTERVAL_SECONDS = 60;

	/** ZINCRBY+EXPIRE 원자 실행 — 분리 2명령이면 ZINCRBY 후 단절 시 TTL 없는 키가 잔존한다. */
	private static final RedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
		"redis.call('ZINCRBY', KEYS[1], 1, ARGV[1]) redis.call('EXPIRE', KEYS[1], ARGV[2]) return 1", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;
	private final Executor executor;
	private final AtomicLong discardedSinceLastLog = new AtomicLong();
	private final AtomicLong nextDiscardLogAtEpochSecond = new AtomicLong();

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
			// 포화가 끝난 뒤에도 밀린 건수가 나가도록 정상 경로에서도 확인한다 — 대개 volatile 읽기 하나다
			reportDiscardsIfDue();
		} catch (RejectedExecutionException e) {
			// 설계가 허용한 폐기(D4)라 스택 추적을 만들지 않는다 — 건수만 세고 주기 요약에 맡긴다 (MSG-330)
			discardedSinceLastLog.incrementAndGet();
			reportDiscardsIfDue();
		} catch (Exception e) {
			log.warn("핫스코어 증분 실패 — 신호 1건 유실 허용: gridId={}", gridId, e);   // FR-6
		}
	}

	/**
	 * 폐기는 건수만 모아 주기 요약한다 (MSG-330). 이 메서드는 요청 스레드에서 도는데, 큐가 포화된
	 * 시점은 이미 밀리는 중이라 건별 로그가 업로드 응답을 붙잡아 상황을 악화시킨다. gridId 를 안 남기는
	 * 건 의도다 — 폐기는 개별 격자의 문제가 아니라 유입이 처리율을 넘었다는 신호다.
	 *
	 * <p>폐기 경로와 정상 경로 양쪽에서 부른다. 폐기 때만 부르면 마지막 구간이 영영 안 나가고,
	 * 정상 때만 부르면 포화가 이어지는 동안 아무 로그도 안 나간다.
	 *
	 * <p>한계: 업로드가 끊긴 채 주기가 지나면 밀린 건수는 다음 업로드나 종료 때까지 안 나간다.
	 * 이 빈에 시간 기반 트리거를 둘 수 없어서다 — {@code @EnableScheduling} 이 ai·notification
	 * 프로파일 조건부라 로컬·CI·현재 prod 에서는 아예 안 뜬다. 포화가 일어났다는 사실 자체는 첫
	 * 폐기에서 이미 나갔으므로 늦어지는 건 정확한 건수뿐이다.
	 */
	private void reportDiscardsIfDue() {
		if (discardedSinceLastLog.get() == 0) {
			return;
		}
		long now = clock.instant().getEpochSecond();
		long due = nextDiscardLogAtEpochSecond.get();
		// CAS 실패 = 다른 스레드가 방금 요약을 찍었다는 뜻이라 이번 건은 그 다음 요약에 합산된다
		if (now < due || !nextDiscardLogAtEpochSecond.compareAndSet(due, now + DISCARD_LOG_INTERVAL_SECONDS)) {
			return;
		}
		log.warn("핫스코어 대기열 포화 — 신호 {}건 폐기 (유실 허용, {}초 주기 요약)",
			discardedSinceLastLog.getAndSet(0), DISCARD_LOG_INTERVAL_SECONDS);
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
		// 주기를 못 채운 잔여분을 여기서 마저 남긴다 — 정상 배포·재기동이면 총계가 로그에 온전히 남는다
		long pendingDiscards = discardedSinceLastLog.getAndSet(0);
		if (pendingDiscards > 0) {
			log.warn("핫스코어 대기열 포화 — 신호 {}건 폐기 (유실 허용, 종료 시점 잔여분)", pendingDiscards);
		}
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
