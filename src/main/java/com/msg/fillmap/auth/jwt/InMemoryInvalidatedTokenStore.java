package com.msg.fillmap.auth.jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 테스트 전용 인메모리 블랙리스트 — 기본(non-test) 빈은 {@link RedisInvalidatedTokenStore}
 * (MSG-135 확정 결정 2).
 */
@Component
@Profile("test")
public class InMemoryInvalidatedTokenStore implements InvalidatedTokenStore {

	private final Map<String, Instant> invalidatedTokens = new ConcurrentHashMap<>();
	private final Map<Long, Long> userInvalidations = new ConcurrentHashMap<>();

	@Override
	public void invalidate(String token, Instant expiresAt) {
		invalidatedTokens.put(token, expiresAt);
	}

	/** ttl 을 버리는 것은 이 구현이 테스트 더블이라서다 — 위 isInvalidated 와 달리 만료를 흉내 내지 않는다. */
	@Override
	public void invalidateUser(Long userId, Instant invalidatedAt, Duration ttl) {
		userInvalidations.put(userId, invalidatedAt.getEpochSecond());
	}

	@Override
	public Long findUserInvalidatedAtEpochSecond(Long userId) {
		return userInvalidations.get(userId);
	}

	@Override
	public boolean isInvalidated(String token) {
		Instant expiresAt = invalidatedTokens.get(token);
		if (expiresAt == null) {
			return false;
		}
		if (expiresAt.isBefore(Instant.now())) {
			invalidatedTokens.remove(token, expiresAt);
			return false;
		}
		return true;
	}
}
