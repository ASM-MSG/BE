package com.msg.fillmap.event.service;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 열람 인원 집계 구현 (MSG-443). 키: {@code eventviewer:{occurrenceId}} Sorted Set,
 * member=세션 식별자, score=마지막 heartbeat 의 epoch 초 (D1). 키 TTL 120초는 조용해진 방의
 * 메모리 회수 전용이고 활성 판정(90초 경계)은 score 범위 연산만 담당한다.
 * 캐시 예외는 격리한다 — heartbeat 는 삼키고 조회는 null 을 돌려준다 (D3, FR-19).
 */
@Slf4j
@Service
public class EventViewerServiceImpl implements EventViewerService {

	private static final String KEY_PREFIX = "eventviewer:";
	/** 활성 판정 창 — 마지막 신호가 90초 이내(경계 포함)인 세션만 센다 (FR-17, FR-18). */
	private static final long ACTIVE_WINDOW_SECONDS = 90L;
	/** 키 TTL — 활성 창과 같은 90초면 경계 멤버가 세어지기 전에 키째 만료된다 (스펙 저장 구조). */
	private static final long KEY_TTL_SECONDS = 120L;

	/**
	 * heartbeat 원자 실행 (D2) — 분리 명령이면 ZADD 후 단절 시 TTL 없는 키가 잔존한다
	 * (핫구역 INCREMENT_SCRIPT 와 같은 이유).
	 * 청소식의 {@code (now-90)} 은 배타 경계라 딱 90초 경과한 멤버를 남기고, 조회 ZCOUNT 의
	 * 포함 경계가 그 멤버를 세어 두 식이 같은 "90초 이내" 판정을 이룬다. ZADD GT 는 네트워크에서
	 * 지연된 옛 heartbeat 가 최신 score 를 과거로 되돌리는 역순 실행을 막는다.
	 * ARGV: [1]=배타 청소 경계 "(now-90)", [2]=now, [3]=세션 식별자, [4]=키 TTL 초.
	 */
	private static final RedisScript<Long> HEARTBEAT_SCRIPT = new DefaultRedisScript<>(
		"redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) "
			+ "redis.call('ZADD', KEYS[1], 'GT', ARGV[2], ARGV[3]) "
			+ "redis.call('EXPIRE', KEYS[1], ARGV[4]) return 1", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;

	@Autowired
	public EventViewerServiceImpl(StringRedisTemplate redisTemplate) {
		this(redisTemplate, Clock.systemUTC());
	}

	/** 결정적 테스트용 — 고정 Clock 주입으로 90초 경계를 재현한다 (D5, 핫구역 선례). */
	public EventViewerServiceImpl(StringRedisTemplate redisTemplate, Clock clock) {
		this.redisTemplate = redisTemplate;
		this.clock = clock;
	}

	@Override
	public void recordHeartbeat(long occurrenceId, String viewerKey) {
		try {
			long now = clock.instant().getEpochSecond();
			redisTemplate.execute(HEARTBEAT_SCRIPT, List.of(KEY_PREFIX + occurrenceId),
				"(" + (now - ACTIVE_WINDOW_SECONDS), String.valueOf(now), viewerKey,
				String.valueOf(KEY_TTL_SECONDS));
		} catch (Exception e) {
			log.warn("열람 heartbeat 실패 — 신호 1건 유실 허용: occurrenceId={}", occurrenceId, e);   // FR-19
		}
	}

	@Override
	public Integer getViewerCount(long occurrenceId) {
		try {
			long cutoff = clock.instant().getEpochSecond() - ACTIVE_WINDOW_SECONDS;
			// ZCOUNT key now-90 +inf — 90초 경계 포함 (D2). 청소는 쓰기 경로 몫이라 조회는 세기만 한다
			Long count = redisTemplate.opsForZSet()
				.count(KEY_PREFIX + occurrenceId, cutoff, Double.POSITIVE_INFINITY);
			return count == null ? null : count.intValue();
		} catch (Exception e) {
			log.warn("열람 인원 조회 실패 — 값 없음으로 응답: occurrenceId={}", occurrenceId, e);   // FR-19
			return null;
		}
	}
}
