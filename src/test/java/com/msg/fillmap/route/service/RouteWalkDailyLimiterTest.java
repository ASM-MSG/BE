package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.msg.fillmap.route.config.RouteWalkProperties;
import com.msg.fillmap.route.service.RouteWalkDailyLimiter.Acquisition;

/**
 * 실제 Redis(localhost:6379)를 사용하는 일 한도 카운터 테스트 (HotScoreCommandServiceImplTest 방식).
 * 고정 Clock 을 과거 시각(2000-01-01)으로 잡아 날짜 키가 실서비스 키와 겹치지 않게 한다.
 */
@DisplayName("RouteWalkDailyLimiter — TMap 일 한도 선점 카운터")
class RouteWalkDailyLimiterTest {

	/** 2000-01-01T00:00:00Z = KST 2000-01-01 09:00 — 키 route:walk:daily:20000101. */
	private static final Instant FIXED_INSTANT = Instant.parse("2000-01-01T00:00:00Z");
	private static final String DAY1_KEY = "route:walk:daily:20000101";
	private static final String DAY2_KEY = "route:walk:daily:20000102";
	private static final int DAILY_LIMIT = 2;

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static RouteWalkDailyLimiter limiter;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		limiter = new RouteWalkDailyLimiter(redisTemplate, properties(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	private static RouteWalkProperties properties() {
		return new RouteWalkProperties(true, "https://tmap.test", "test-app-key", DAILY_LIMIT, Duration.ofSeconds(3));
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(List.of(DAY1_KEY, DAY2_KEY));
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	// 검증: NFR-OPS-09
	@Test
	void 한도_안에서는_선점이_허용되고_사용량이_함께_온다() {
		assertThat(limiter.tryAcquire()).isEqualTo(new Acquisition(true, 1));
		assertThat(limiter.tryAcquire()).isEqualTo(new Acquisition(true, 2));
	}

	// 검증: FR-ROUTE-17, NFR-OPS-09
	@Test
	void 일_한도를_넘는_선점은_거부된다() {
		limiter.tryAcquire();
		limiter.tryAcquire();

		Acquisition denied = limiter.tryAcquire();

		assertThat(denied.allowed()).isFalse();
		assertThat(denied.dailyUsed()).isEqualTo(DAILY_LIMIT + 1);
	}

	// 검증: NFR-OPS-09
	@Test
	void 한도_카운터_키에는_TTL이_함께_걸린다() {
		limiter.tryAcquire();

		Long expireSeconds = redisTemplate.getExpire(DAY1_KEY);

		// INCR·EXPIRE 는 Lua 원자 실행 — 분리 2명령의 TTL 없는 키 잔존을 막는다 (핫존 버킷 TTL 테스트 선례).
		assertThat(expireSeconds).isBetween(Duration.ofHours(47).toSeconds(), Duration.ofHours(48).toSeconds());
	}

	// 검증: NFR-OPS-09
	@Test
	void KST_자정이_지나면_한도_카운터가_새_날짜_키로_넘어간다() {
		// 2000-01-01T14:59Z = KST 01일 23:59, 15:00Z = KST 02일 00:00 — UTC 날짜는 아직 01일이다.
		RouteWalkDailyLimiter beforeMidnight = new RouteWalkDailyLimiter(redisTemplate, properties(),
			Clock.fixed(Instant.parse("2000-01-01T14:59:00Z"), ZoneOffset.UTC));
		RouteWalkDailyLimiter afterMidnight = new RouteWalkDailyLimiter(redisTemplate, properties(),
			Clock.fixed(Instant.parse("2000-01-01T15:00:00Z"), ZoneOffset.UTC));

		beforeMidnight.tryAcquire();
		beforeMidnight.tryAcquire();
		assertThat(beforeMidnight.tryAcquire().allowed()).isFalse();   // 01일 한도 소진

		// 자정 리셋은 날짜 키 전환으로 성립한다 — 새 날짜의 첫 선점은 카운트 1부터 다시 센다.
		assertThat(afterMidnight.tryAcquire()).isEqualTo(new Acquisition(true, 1));
		assertThat(redisTemplate.opsForValue().get(DAY1_KEY)).isEqualTo("3");
		assertThat(redisTemplate.opsForValue().get(DAY2_KEY)).isEqualTo("1");
	}

	// 검증: NFR-OPS-09, FR-ROUTE-17
	@Test
	void 한도_카운터의_Redis_오류_중에는_선점이_거부된다() {
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
		deadFactory.afterPropertiesSet();
		StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
		deadTemplate.afterPropertiesSet();
		RouteWalkDailyLimiter deadLimiter = new RouteWalkDailyLimiter(deadTemplate, properties(),
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

		// 계수 불능 상태에서 호출을 열어두면 한도 방어 자체가 사라진다 — 캐시(미스 취급)와 반대 방향의 보수 폴백.
		Acquisition denied = deadLimiter.tryAcquire();

		assertThat(denied.allowed()).isFalse();
		assertThat(denied.dailyUsed()).isEqualTo(-1);

		deadFactory.destroy();
	}
}
