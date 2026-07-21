package com.msg.fillmap.auth.jwt;

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

	@Override
	public void invalidate(String token, Instant expiresAt) {
		invalidatedTokens.put(token, expiresAt);
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
