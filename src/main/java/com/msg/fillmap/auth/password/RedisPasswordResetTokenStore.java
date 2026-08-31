package com.msg.fillmap.auth.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Redis 재설정 토큰 저장소 (MSG-497). 정방향 {@code pwreset:{sha256hex(token)}} → userId,
 * 역방향 {@code pwreset:user:{userId}} → 토큰 해시. 둘 다 TTL 30분이고 원문은 어느 쪽에도 없다.
 *
 * <p>네 메서드가 전부 Lua 스크립트 하나다. GET 후 DEL 후 SET 으로 조립하면 save 와 consume 이 동시에
 * 돌 때 역방향 포인터가 어긋난다 — save(새 토큰 B)가 역방향을 읽은 직후 consume(토큰 A)이 역방향을
 * 지우면, save 가 마저 쓴 B 의 정방향만 남고 역방향이 없어져 revoke 가 B 를 못 찾는다(폐기 불가 링크
 * 잔존). 같은 read-modify-write 원자화 선례가 이 레포에 다섯 곳 있다(RouteWalkDailyLimiter 등).
 *
 * <p>스크립트 안에서 접두어와 값을 이어 붙여 만드는 키(정방향·역방향 상대편)는 KEYS 로 선언하지
 * 않는다 — 값을 읽어야 키를 알 수 있어서다. 단일 노드 Redis 전제이고, 클러스터로 옮기면 해시 태그가
 * 필요하다.
 *
 * <p>Redis 유실 시 토큰이 사라져 사용자가 재요청하면 된다(핫스코어의 유실 허용과 같은 수용) —
 * DB 백업 저장을 두지 않는 근거다.
 */
@Component
@RequiredArgsConstructor
public class RedisPasswordResetTokenStore implements PasswordResetTokenStore {

	static final String FORWARD_PREFIX = "pwreset:";
	static final String REVERSE_PREFIX = "pwreset:user:";
	static final String COOLDOWN_PREFIX = "pwreset-cooldown:";
	static final Duration TTL = Duration.ofMinutes(30);
	static final Duration COOLDOWN = Duration.ofSeconds(60);

	/** 역방향에 남은 옛 해시로 그 정방향을 지우고 두 키를 새로 쓴다 — 사용자당 활성 1개 강제. */
	private static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
		local old = redis.call('GET', KEYS[2])
		if old then redis.call('DEL', ARGV[3] .. old) end
		redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
		redis.call('SET', KEYS[2], ARGV[4], 'EX', ARGV[2])
		return 1
		""", Long.class);

	/**
	 * 정방향을 선점하고, 역방향이 <b>소비한 그 해시일 때만</b> 함께 지운다(compare-and-delete).
	 * 일치하지 않으면 이미 새 토큰으로 교체된 것이라 남겨 둔다 — 지우면 새 링크가 폐기 불가가 된다.
	 */
	private static final RedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
		local userId = redis.call('GET', KEYS[1])
		if not userId then return nil end
		redis.call('DEL', KEYS[1])
		local reverseKey = ARGV[1] .. userId
		if redis.call('GET', reverseKey) == ARGV[2] then redis.call('DEL', reverseKey) end
		return userId
		""", String.class);

	private static final RedisScript<Long> REVOKE_SCRIPT = new DefaultRedisScript<>("""
		local hash = redis.call('GET', KEYS[1])
		if hash then redis.call('DEL', ARGV[1] .. hash) end
		redis.call('DEL', KEYS[1])
		return 1
		""", Long.class);

	/** 역방향이 비어 있을 때만 되살린다(compare-and-restore) — 새로 발급된 링크를 되덮지 않는다. */
	private static final RedisScript<Long> RESTORE_SCRIPT = new DefaultRedisScript<>("""
		if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
		redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
		redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])
		return 1
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	@Override
	public void save(String token, Long userId) {
		String hash = sha256Hex(token);
		redisTemplate.execute(SAVE_SCRIPT, List.of(FORWARD_PREFIX + hash, REVERSE_PREFIX + userId),
			String.valueOf(userId), String.valueOf(TTL.toSeconds()), FORWARD_PREFIX, hash);
	}

	@Override
	public Long consume(String token) {
		String hash = sha256Hex(token);
		String userId = redisTemplate.execute(CONSUME_SCRIPT, List.of(FORWARD_PREFIX + hash),
			REVERSE_PREFIX, hash);
		return userId == null ? null : Long.valueOf(userId);
	}

	@Override
	public void revoke(Long userId) {
		redisTemplate.execute(REVOKE_SCRIPT, List.of(REVERSE_PREFIX + userId), FORWARD_PREFIX);
	}

	@Override
	public void restore(String token, Long userId) {
		String hash = sha256Hex(token);
		redisTemplate.execute(RESTORE_SCRIPT, List.of(FORWARD_PREFIX + hash, REVERSE_PREFIX + userId),
			String.valueOf(userId), String.valueOf(TTL.toSeconds()), hash);
	}

	@Override
	public boolean tryAcquireCooldown(String email) {
		// SET NX EX 한 명령이 원자라 Lua 가 불요하다 — 읽고 판단해서 쓰는 절차가 없다.
		return Boolean.TRUE.equals(
			redisTemplate.opsForValue().setIfAbsent(COOLDOWN_PREFIX + sha256Hex(email), "1", COOLDOWN));
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
