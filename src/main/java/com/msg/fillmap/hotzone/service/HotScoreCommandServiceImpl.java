package com.msg.fillmap.hotzone.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 핫스코어 집계 구현 (MSG-183). 키: {@code hotzone:{bucketId}} Sorted Set, member=gridId.
 * bucketId = epochSeconds / 21600 — UTC 6h 고정 버킷 (D2). TTL 54h 는 청소 전용이며
 * 48h 윈도우 판정은 조회(MSG-184) 몫 (D4). 실패는 삼키고 warn 로깅만 한다 (D6, FR-6).
 */
@Slf4j
@Service
public class HotScoreCommandServiceImpl implements HotScoreCommandService {

	private static final String KEY_PREFIX = "hotzone:";
	private static final long BUCKET_SECONDS = 21600L;
	private static final Duration BUCKET_TTL = Duration.ofHours(54);

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;

	@Autowired
	public HotScoreCommandServiceImpl(StringRedisTemplate redisTemplate) {
		this(redisTemplate, Clock.systemUTC());
	}

	/** 버킷 경계(6h) 결정적 테스트용 — 고정 Clock 주입 (MSG-183 §테스트 환경). */
	public HotScoreCommandServiceImpl(StringRedisTemplate redisTemplate, Clock clock) {
		this.redisTemplate = redisTemplate;
		this.clock = clock;
	}

	@Override
	public void recordUpload(String gridId) {
		try {
			String key = KEY_PREFIX + clock.instant().getEpochSecond() / BUCKET_SECONDS;
			redisTemplate.opsForZSet().incrementScore(key, gridId, 1);
			redisTemplate.expire(key, BUCKET_TTL);
		} catch (Exception e) {
			log.warn("핫스코어 증분 실패 — 신호 1건 유실 허용: gridId={}", gridId, e);   // FR-6
		}
	}
}
