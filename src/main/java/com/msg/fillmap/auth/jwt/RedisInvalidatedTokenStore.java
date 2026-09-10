package com.msg.fillmap.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Redis 액세스 토큰 블랙리스트 (MSG-135). 키: {@code blacklist:{sha256hex(token)}}, TTL = 잔여 만료.
 *
 * <p>액세스 토큰에는 jti 가 없어 토큰 해시를 키로 쓴다 — 원문을 키에 넣지 않아 길이·노출 문제를 피한다.
 *
 * <p>사용자 단위 무효화(MSG-497)는 키 {@code blacklist:user:{userId}} 에 무효화 시각(epoch 초)을 담는다.
 * 토큰 하나가 아니라 그 시각 이전 발급분 전체가 대상이라 값이 플래그가 아니라 시각이다.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisInvalidatedTokenStore implements InvalidatedTokenStore {

	private static final String KEY_PREFIX = "blacklist:";
	private static final String USER_KEY_PREFIX = "blacklist:user:";
	private static final String PRESENT = "1";

	private final StringRedisTemplate redisTemplate;

	@Override
	public void invalidate(String token, Instant expiresAt) {
		Duration ttl = Duration.between(Instant.now(), expiresAt);
		if (ttl.isNegative() || ttl.isZero()) {
			return;
		}
		redisTemplate.opsForValue().set(key(token), PRESENT, ttl);
	}

	@Override
	public boolean isInvalidated(String token) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(key(token)));
	}

	@Override
	public void invalidateUser(Long userId, Instant invalidatedAt, Duration ttl) {
		redisTemplate.opsForValue()
			.set(USER_KEY_PREFIX + userId, String.valueOf(invalidatedAt.getEpochSecond()), ttl);
	}

	@Override
	public Long findUserInvalidatedAtEpochSecond(Long userId) {
		String value = redisTemplate.opsForValue().get(USER_KEY_PREFIX + userId);
		return value == null ? null : Long.valueOf(value);
	}

	private String key(String token) {
		return KEY_PREFIX + sha256Hex(token);
	}

	private String sha256Hex(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 입니다", e);
		}
	}
}
