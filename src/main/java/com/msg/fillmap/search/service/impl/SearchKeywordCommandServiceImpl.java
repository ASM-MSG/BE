package com.msg.fillmap.search.service.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.search.repository.SearchKeywordDailyCountRepository;
import com.msg.fillmap.search.service.SearchKeywordCommandService;

/**
 * 검색어 집계 구현 (MSG-258). dedupe 는 Redis SET {@code searchdedupe:{yyyyMMdd}}(KST 날짜),
 * member={userId}:{정규화 검색어}, TTL 26h 는 청소 전용이다 — 판정은 키의 날짜가 담당한다(§D4).
 * SADD 가 1(오늘 첫 검색)일 때만 DB 카운트를 +1 하고, 0이면 스킵한다(FR-2).
 *
 * Redis·DB 호출은 자체 단일 데몬 스레드로 분리한다 — 동기면 검색 응답에 왕복 2회가 붙고 저장소 장애 시
 * 타임아웃만큼 검색이 블록돼 "검색 응답은 집계와 독립"(FR-6)이 깨진다. 큐 포화·저장소 예외는 전부 삼키고
 * warn 만 남긴다(신호 1건 유실 허용 — 순위는 근사 지표).
 */
@Slf4j
@Service
public class SearchKeywordCommandServiceImpl implements SearchKeywordCommandService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final String KEY_PREFIX = "searchdedupe:";
	private static final long DEDUPE_TTL_SECONDS = Duration.ofHours(26).toSeconds();
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final int MAX_KEYWORD_LENGTH = 255;
	private static final int QUEUE_CAPACITY = 10_000;
	private static final long DRAIN_TIMEOUT_SECONDS = 5;

	/** SADD+EXPIRE 원자 실행 — 분리 2명령이면 SADD 후 단절 시 TTL 없는 키가 잔존한다. 반환은 SADD 결과. */
	private static final RedisScript<Long> DEDUPE_SCRIPT = new DefaultRedisScript<>(
		"local added = redis.call('SADD', KEYS[1], ARGV[1]) redis.call('EXPIRE', KEYS[1], ARGV[2]) return added",
		Long.class);

	private final StringRedisTemplate redisTemplate;
	private final SearchKeywordDailyCountRepository searchKeywordDailyCountRepository;
	private final Clock clock;
	private final Executor executor;

	@Autowired
	public SearchKeywordCommandServiceImpl(StringRedisTemplate redisTemplate,
		SearchKeywordDailyCountRepository searchKeywordDailyCountRepository) {
		this(redisTemplate, searchKeywordDailyCountRepository, Clock.systemUTC(), defaultExecutor());
	}

	/** 결정적 테스트용 — 고정 Clock(KST 날짜 경계)·same-thread Executor 주입 (MSG-258 §테스트 시나리오). */
	public SearchKeywordCommandServiceImpl(StringRedisTemplate redisTemplate,
		SearchKeywordDailyCountRepository searchKeywordDailyCountRepository, Clock clock, Executor executor) {
		this.redisTemplate = redisTemplate;
		this.searchKeywordDailyCountRepository = searchKeywordDailyCountRepository;
		this.clock = clock;
		this.executor = executor;
	}

	@Override
	public void recordSearch(long userId, String rawQuery) {
		try {
			String keyword = normalize(rawQuery);
			if (keyword.length() > MAX_KEYWORD_LENGTH) {
				return;   // 컬럼 폭 초과 — 실질 검색어가 아니라 순위 재료 가치가 없다 (§D3)
			}
			// 날짜는 접수 시각(요청 스레드)에 확정 — 큐가 밀려 워커가 KST 자정을 넘겨 실행돼도 검색 시각의 날짜에 기록
			LocalDate date = LocalDate.now(clock.withZone(KST));
			executor.execute(() -> aggregate(date, userId, keyword));
		} catch (Exception e) {
			// 큐 포화(RejectedExecutionException) 포함 — 폐기하고 warn 만 남긴다 (FR-6)
			log.warn("검색어 집계 접수 실패 — 신호 1건 유실 허용: userId={}", userId, e);
		}
	}

	/** 워커 스레드 실행 — clock 을 읽지 않는다(날짜는 접수 시각에 이미 확정). */
	private void aggregate(LocalDate date, long userId, String keyword) {
		try {
			Long added = redisTemplate.execute(DEDUPE_SCRIPT, List.of(KEY_PREFIX + date.format(KEY_DATE_FORMAT)),
				userId + ":" + keyword, String.valueOf(DEDUPE_TTL_SECONDS));
			if (added == null || added == 0L) {
				return;   // 오늘 이 사용자가 이미 검색한 검색어 (FR-2)
			}
			searchKeywordDailyCountRepository.upsertIncrement(date, keyword);
		} catch (Exception e) {
			// Redis 실패면 카운트도 진행하지 않는다 — dedupe 없이 세면 도배 방어가 뚫린다 (§D4)
			log.warn("검색어 집계 실패 — 신호 1건 유실 허용: keyword={}", keyword, e);
		}
	}

	/** trim → 연속 공백 1칸 압축 → 소문자(Locale.ROOT — 기본 로케일 의존, 터키 i 문제 차단) (§D3). */
	private static String normalize(String rawQuery) {
		return WHITESPACE.matcher(rawQuery.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
	}

	@PreDestroy
	void shutdown() {
		if (!(executor instanceof ExecutorService executorService)) {
			return;
		}
		// 일상 배포에선 접수한 신호를 드레인하고, 타임아웃·인터럽트에서만 유실을 허용한다
		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				log.warn("검색어 집계 드레인 타임아웃 — 잔여 신호 {}건 유실 허용", executorService.shutdownNow().size());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("검색어 집계 드레인 인터럽트 — 잔여 신호 {}건 유실 허용", executorService.shutdownNow().size());
		}
	}

	private static ThreadPoolExecutor defaultExecutor() {
		// ponytail: 단일 스레드 직렬 처리 — 검색 TPS 수준에선 충분, 밀리면 스레드 수 증설
		return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(QUEUE_CAPACITY),
			runnable -> {
				Thread thread = new Thread(runnable, "search-keyword");
				thread.setDaemon(true);
				return thread;
			});
	}
}
