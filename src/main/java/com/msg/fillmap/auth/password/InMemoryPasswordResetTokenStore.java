package com.msg.fillmap.auth.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 테스트 전용 인메모리 재설정 토큰 저장소 — 기본(non-test) 빈은 {@link RedisPasswordResetTokenStore}
 * (InMemoryRefreshTokenStore 선례). TTL 과 쿨다운 만료는 시뮬레이션하지 않되, 두 키의 정합 규칙
 * (사용자당 활성 1개 · compare-and-delete · compare-and-restore)은 Redis 구현과 동일하다.
 */
@Component
@Profile("test")
public class InMemoryPasswordResetTokenStore implements PasswordResetTokenStore {

	/** 정방향 — 토큰 해시 → userId. */
	private final Map<String, Long> forward = new ConcurrentHashMap<>();
	/** 역방향 — userId → 토큰 해시. */
	private final Map<Long, String> reverse = new ConcurrentHashMap<>();
	private final Map<String, Boolean> cooldowns = new ConcurrentHashMap<>();

	@Override
	public synchronized void save(String token, Long userId) {
		String previous = reverse.get(userId);
		if (previous != null) {
			forward.remove(previous);
		}
		String hash = sha256Hex(token);
		forward.put(hash, userId);
		reverse.put(userId, hash);
	}

	@Override
	public synchronized Long consume(String token) {
		String hash = sha256Hex(token);
		Long userId = forward.remove(hash);
		if (userId == null) {
			return null;
		}
		reverse.remove(userId, hash);
		return userId;
	}

	@Override
	public synchronized void revoke(Long userId) {
		String hash = reverse.remove(userId);
		if (hash != null) {
			forward.remove(hash);
		}
	}

	@Override
	public synchronized void restore(String token, Long userId) {
		if (reverse.containsKey(userId)) {
			return;
		}
		String hash = sha256Hex(token);
		forward.put(hash, userId);
		reverse.put(userId, hash);
	}

	@Override
	public boolean tryAcquireCooldown(String email) {
		return cooldowns.putIfAbsent(sha256Hex(email), Boolean.TRUE) == null;
	}

	private String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 입니다", e);
		}
	}
}
