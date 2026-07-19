package com.msg.fillmap.auth.jwt;

import java.time.Duration;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Redis 리프레시 세션 저장소 (MSG-135). 키: {@code refresh:{userId}:{deviceId}}, 값: 현재 jti.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

	private static final String KEY_PREFIX = "refresh:";
	private static final long SCAN_COUNT = 100;

	private final StringRedisTemplate redisTemplate;

	@Override
	public void save(Long userId, String deviceId, String jti, Duration ttl) {
		redisTemplate.opsForValue().set(key(userId, deviceId), jti, ttl);
	}

	@Override
	public String findJti(Long userId, String deviceId) {
		return redisTemplate.opsForValue().get(key(userId, deviceId));
	}

	@Override
	public void delete(Long userId, String deviceId) {
		redisTemplate.delete(key(userId, deviceId));
	}

	@Override
	public void deleteAll(Long userId) {
		// 로그아웃-올 폴백 — KEYS 는 금지, SCAN 으로 순회한다 (MSG-135 확정 결정 5)
		ScanOptions options = ScanOptions.scanOptions()
			.match(KEY_PREFIX + userId + ":*")
			.count(SCAN_COUNT)
			.build();
		try (Cursor<String> cursor = redisTemplate.scan(options)) {
			while (cursor.hasNext()) {
				redisTemplate.delete(cursor.next());
			}
		}
	}

	private String key(Long userId, String deviceId) {
		return KEY_PREFIX + userId + ":" + deviceId;
	}
}
