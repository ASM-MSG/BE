package com.msg.fillmap.auth.jwt;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 테스트 전용 인메모리 리프레시 세션 저장소 — 슬라이스/서비스 테스트를 Redis-free 로 유지한다
 * (MSG-135 확정 결정 2). TTL 은 시뮬레이션하지 않는다.
 */
@Component
@Profile("test")
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

	private final Map<String, String> sessions = new ConcurrentHashMap<>();

	@Override
	public void save(Long userId, String deviceId, String jti, Duration ttl) {
		sessions.put(key(userId, deviceId), jti);
	}

	@Override
	public String findJti(Long userId, String deviceId) {
		return sessions.get(key(userId, deviceId));
	}

	@Override
	public void delete(Long userId, String deviceId) {
		sessions.remove(key(userId, deviceId));
	}

	@Override
	public void deleteAll(Long userId) {
		sessions.keySet().removeIf(key -> key.startsWith(userId + ":"));
	}

	private String key(Long userId, String deviceId) {
		return userId + ":" + deviceId;
	}
}
