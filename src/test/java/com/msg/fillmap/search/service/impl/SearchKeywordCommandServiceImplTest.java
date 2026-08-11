package com.msg.fillmap.search.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.msg.fillmap.search.repository.SearchKeywordDailyCountRepository;

/**
 * 검색어 집계 서비스 테스트 (MSG-258) — 실제 Redis(localhost:6379) dedupe + mock 리포지토리.
 * 고정 Clock 을 과거 시각(2000-01-01)으로 잡아 dedupe 키가 실서비스·타 테스트 키와 겹치지 않게 하고,
 * Executor 는 same-thread(Runnable::run) 주입으로 비동기 플레이크 없이 결정적으로 단언한다
 * (HotScoreCommandServiceImplTest 선례). 잔여 키는 tearDown 에서 정리 — 남으면 dedupe 판정이 오염된다.
 */
@DisplayName("SearchKeywordCommandServiceImpl 검색어 집계")
class SearchKeywordCommandServiceImplTest {

	/** 2000-01-01T14:00:00Z = KST 2000-01-01 23:00 — 실서비스 날짜 키와 격리된 과거 시각. */
	private static final Instant FIXED_INSTANT = Instant.parse("2000-01-01T14:00:00Z");
	private static final LocalDate KST_DATE = LocalDate.of(2000, 1, 1);
	private static final LocalDate NEXT_KST_DATE = LocalDate.of(2000, 1, 2);
	private static final String DEDUPE_KEY = "searchdedupe:20000101";
	private static final String NEXT_DEDUPE_KEY = "searchdedupe:20000102";
	private static final long USER_ID = 42L;
	private static final long OTHER_USER_ID = 43L;

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;

	private SearchKeywordDailyCountRepository repository;
	private SearchKeywordCommandServiceImpl service;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
	}

	@BeforeEach
	void setUp() {
		repository = mock(SearchKeywordDailyCountRepository.class);
		service = new SearchKeywordCommandServiceImpl(redisTemplate, repository,
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), Runnable::run);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(List.of(DEDUPE_KEY, NEXT_DEDUPE_KEY));
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	// 검증: FR-SEARCH-05
	@Test
	void 유효한_검색어_1건은_오늘_날짜의_카운트를_올린다() {
		service.recordSearch(USER_ID, "부산대");

		verify(repository).upsertIncrement(KST_DATE, "부산대");
	}

	// 검증: FR-SEARCH-06
	@Test
	void 연속_공백과_대소문자가_다른_검색어는_같은_검색어로_정규화되어_합산된다() {
		service.recordSearch(USER_ID, "  홍대   Cafe  ");
		service.recordSearch(OTHER_USER_ID, "홍대 cafe");

		verify(repository, times(2)).upsertIncrement(KST_DATE, "홍대 cafe");
	}

	// 검증: FR-SEARCH-06
	@Test
	void 같은_사용자가_같은_날_같은_검색어를_두번_검색해도_카운트는_한번만_오른다() {
		service.recordSearch(USER_ID, "부산대");
		service.recordSearch(USER_ID, "부산대");

		verify(repository, times(1)).upsertIncrement(KST_DATE, "부산대");
	}

	// 검증: FR-SEARCH-06
	@Test
	void 다른_사용자의_같은_검색어는_각각_카운트된다() {
		service.recordSearch(USER_ID, "부산대");
		service.recordSearch(OTHER_USER_ID, "부산대");

		verify(repository, times(2)).upsertIncrement(KST_DATE, "부산대");
	}

	@Test
	void 정규화_후_255자를_넘는_검색어는_집계에서_제외된다() {
		service.recordSearch(USER_ID, "가".repeat(256));

		verifyNoInteractions(repository);
		assertThat(redisTemplate.hasKey(DEDUPE_KEY)).isFalse();
	}

	@Test
	void 정규화_후_255자인_검색어는_집계된다() {
		String keyword = "가".repeat(255);

		service.recordSearch(USER_ID, keyword);

		verify(repository).upsertIncrement(KST_DATE, keyword);
	}

	// 검증: FR-SEARCH-05
	@Test
	void 집계_날짜는_KST_기준으로_접수_시점에_확정된다() {
		AtomicReference<Instant> now = new AtomicReference<>(FIXED_INSTANT);
		Clock mutableClock = new Clock() {
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
		};
		List<Runnable> deferred = new ArrayList<>();
		SearchKeywordCommandServiceImpl delayedService =
			new SearchKeywordCommandServiceImpl(redisTemplate, repository, mutableClock, deferred::add);

		delayedService.recordSearch(USER_ID, "부산대");
		now.set(FIXED_INSTANT.plus(Duration.ofHours(2)));   // 워커 실행 전에 KST 자정을 넘긴다
		deferred.forEach(Runnable::run);

		verify(repository).upsertIncrement(KST_DATE, "부산대");
		verify(repository, times(0)).upsertIncrement(NEXT_KST_DATE, "부산대");
	}

	@Test
	void dedupe_키에_26시간_TTL이_설정된다() {
		service.recordSearch(USER_ID, "부산대");

		Long expireSeconds = redisTemplate.getExpire(DEDUPE_KEY);

		assertThat(expireSeconds).isBetween(Duration.ofHours(25).toSeconds(), Duration.ofHours(26).toSeconds());
	}

	@Test
	void dedupe_표식은_사용자와_정규화_검색어로_구성된다() {
		service.recordSearch(USER_ID, "  부산대  ");

		assertThat(redisTemplate.opsForSet().members(DEDUPE_KEY)).containsExactly(USER_ID + ":부산대");
	}

	// 검증: FR-SEARCH-10
	@Test
	void 레디스_장애면_카운트를_올리지_않고_예외도_전파하지_않는다() {
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
		deadFactory.afterPropertiesSet();
		StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
		deadTemplate.afterPropertiesSet();
		SearchKeywordCommandServiceImpl deadService = new SearchKeywordCommandServiceImpl(deadTemplate, repository,
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), Runnable::run);

		assertThatCode(() -> deadService.recordSearch(USER_ID, "부산대")).doesNotThrowAnyException();

		verifyNoInteractions(repository);   // dedupe 판정 없이 카운트하면 도배 방어가 뚫린다 (D4)
		deadFactory.destroy();
	}

	// 검증: FR-SEARCH-10
	@Test
	void 집계_실패_로그에_검색어_원문이_남지_않는다() {
		// 실패 예외 메시지는 바인딩 값(검색어)을 에코할 수 있다 — 예: Postgres UNIQUE 위반 DETAIL (MSG-342 D-2)
		when(repository.upsertIncrement(any(), anyString()))
			.thenThrow(new RuntimeException("Key (keyword)=(부산대) already exists"));
		ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
		logAppender.start();
		Logger logger = (Logger) LoggerFactory.getLogger(SearchKeywordCommandServiceImpl.class);
		logger.addAppender(logAppender);
		try {
			service.recordSearch(USER_ID, "부산대");

			ILoggingEvent event = logAppender.list.get(0);
			assertThat(event.getThrowableProxy()).isNull();   // 스택·예외 메시지 자체를 로그에 싣지 않는다
			assertThat(event.getFormattedMessage())
				.doesNotContain("부산대")
				.contains("userId=" + USER_ID)
				.contains("causeType=RuntimeException");   // 원인 구분 손잡이는 클래스명으로 유지
		} finally {
			logger.detachAppender(logAppender);
		}
	}

	@Test
	void 집계_DB_장애에도_예외를_전파하지_않는다() {
		when(repository.upsertIncrement(any(), anyString())).thenThrow(new RuntimeException("DB 장애"));

		assertThatCode(() -> service.recordSearch(USER_ID, "부산대")).doesNotThrowAnyException();
	}

	// 검증: FR-SEARCH-10
	@Test
	void 큐가_포화되면_예외_없이_신호를_버린다() {
		SearchKeywordCommandServiceImpl saturatedService = new SearchKeywordCommandServiceImpl(redisTemplate,
			repository, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
			task -> {
				throw new RejectedExecutionException("큐 포화");
			});

		assertThatCode(() -> saturatedService.recordSearch(USER_ID, "부산대")).doesNotThrowAnyException();

		verifyNoInteractions(repository);
	}

	@Test
	void 종료_드레인_타임아웃이면_강제_종료한다() throws InterruptedException {
		ExecutorService stuckExecutor = mock(ExecutorService.class);
		when(stuckExecutor.awaitTermination(anyLong(), any())).thenReturn(false);
		when(stuckExecutor.shutdownNow()).thenReturn(List.of());
		SearchKeywordCommandServiceImpl stuckService = new SearchKeywordCommandServiceImpl(redisTemplate, repository,
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), stuckExecutor);

		stuckService.shutdown();

		verify(stuckExecutor).shutdownNow();
	}

	@Test
	void 종료_대기_중_인터럽트되면_플래그를_복원하고_강제_종료한다() throws InterruptedException {
		ExecutorService interruptedExecutor = mock(ExecutorService.class);
		when(interruptedExecutor.awaitTermination(anyLong(), any())).thenThrow(new InterruptedException());
		when(interruptedExecutor.shutdownNow()).thenReturn(List.of());
		SearchKeywordCommandServiceImpl interruptedService = new SearchKeywordCommandServiceImpl(redisTemplate,
			repository, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), interruptedExecutor);

		interruptedService.shutdown();

		assertThat(Thread.interrupted()).isTrue();   // 복원 단언 겸 플래그 클리어 — 후속 테스트 오염 방지
		verify(interruptedExecutor).shutdownNow();
	}

	@Test
	void ExecutorService가_아닌_Executor면_종료는_아무_동작도_하지_않는다() {
		assertThatCode(service::shutdown).doesNotThrowAnyException();
	}

	@Test
	void 기본_생성자로_만든_서비스는_종료_시_큐를_드레인하고_끝난다() {
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
		deadFactory.afterPropertiesSet();
		StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
		deadTemplate.afterPropertiesSet();
		SearchKeywordCommandServiceImpl defaultService = new SearchKeywordCommandServiceImpl(deadTemplate, repository);

		// 죽은 포트 템플릿 — 워커 스레드는 실제로 생성·실행되지만 실서비스 dedupe 키를 오염시키지 않는다
		defaultService.recordSearch(USER_ID, "부산대");

		assertThatCode(defaultService::shutdown).doesNotThrowAnyException();

		deadFactory.destroy();
	}
}
