package com.msg.fillmap.route.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.route.config.RouteWalkProperties;

/**
 * TMap 일 한도 선점 카운터 (MSG-483 §도메인 로직 2, FR-9·NFR-OPS-09). Redis 키
 * {@code route:walk:daily:{yyyyMMdd}}(KST 날짜 — TMap 무료 한도 리셋 주기가 일 단위이고 SK 국내 서비스라
 * KST 기준). 자정 리셋은 날짜 키 전환으로 성립하고 지난 키는 TTL 48시간으로 소멸한다. 선점은 TMap 호출
 * 직전이고 호출이 실패해도 되돌리지 않는다 — 시도가 나갔으면 외부 한도는 이미 소모됐다는 보수 계상이다.
 *
 * Redis 오류는 차단이다(선점 거부 → 그 세그먼트 실패, 직선 폴백). 한도는 열어두면 방어 자체가 사라지는
 * 안전장치라 캐시(미스 취급)와 폴백 방향이 반대다 — 근거 대비는 RouteWalkSegmentCache 주석 참조.
 */
@Slf4j
@Component
public class RouteWalkDailyLimiter {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final String KEY_PREFIX = "route:walk:daily:";
	private static final long TTL_SECONDS = Duration.ofHours(48).toSeconds();

	/**
	 * INCR+EXPIRE 원자 실행 — 분리 2명령이면 INCR 직후 크래시·연결 유실 때 TTL 없는 키가 영구 잔존해
	 * 명시한 48시간 수명과 모순이다 (HotScoreCommandServiceImpl.INCREMENT_SCRIPT 선례, 근거 동일).
	 */
	private static final RedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
		"local count = redis.call('INCR', KEYS[1]) redis.call('EXPIRE', KEYS[1], ARGV[1]) return count", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final RouteWalkProperties properties;
	private final Clock clock;

	@Autowired
	public RouteWalkDailyLimiter(StringRedisTemplate redisTemplate, RouteWalkProperties properties) {
		this(redisTemplate, properties, Clock.systemUTC());
	}

	/** 결정적 테스트용 — 고정 Clock(KST 날짜 경계) 주입 (SearchKeywordCommandServiceImpl 선례). */
	public RouteWalkDailyLimiter(StringRedisTemplate redisTemplate, RouteWalkProperties properties, Clock clock) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * TMap 호출 직전 선점. 허용이면 dailyUsed 가 이번 선점 후 오늘 사용량이다(지표 로그 daily_used 재료).
	 * 한도 초과와 Redis 오류(dailyUsed -1)는 거부 — 호출자는 그 세그먼트를 실패 처리한다.
	 */
	public Acquisition tryAcquire() {
		String key = KEY_PREFIX + LocalDate.now(clock.withZone(KST)).format(KEY_DATE_FORMAT);
		try {
			Long count = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(key), String.valueOf(TTL_SECONDS));
			if (count == null) {
				return refused("null 반환");
			}
			return new Acquisition(count <= properties.dailyLimit(), count);
		} catch (Exception e) {
			return refused(e.getClass().getSimpleName());
		}
	}

	private Acquisition refused(String cause) {
		log.warn("[route-walk] 한도 카운터 Redis 오류 — 호출 차단 (보수 폴백): cause={}", cause);
		return new Acquisition(false, -1);
	}

	/** 선점 결과 — allowed 가 false 면 호출 금지. dailyUsed 는 Redis 오류일 때만 -1. */
	public record Acquisition(boolean allowed, long dailyUsed) {
	}
}
