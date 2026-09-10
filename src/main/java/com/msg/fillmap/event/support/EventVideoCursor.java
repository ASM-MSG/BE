package com.msg.fillmap.event.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 위치별 영상 피드 keyset 커서 (MSG-440 §API 2). 마지막으로 내려준 항목의 경계값 (locationId, createdAt, id)
 * 을 Base64URL("{locationId}:{createdAtEpochMicros}:{id}") 로 감싼 opaque 토큰이다 — VideoCursor(MSG-237) ·
 * MissionVideoCursor(MSG-390) 의 형식과 규칙을 그대로 물려받되 성분의 뜻이 달라 별도 타입이다.
 * locationId 성분은 <b>발급 위치 바인딩</b>이다 — 다른 위치 요청에 재사용된 커서는 경계값이 그 위치의 keyset
 * 으로 오적용돼 결과가 조용히 잘리므로, 서비스가 요청 locationId 와 대조해 거부한다(2026-07-28 Codex 교차
 * 리뷰 P2 가 격자 축에서 도입한 규칙과 같다).
 * 시각 성분은 ISO 문자열이 아니라 epoch 마이크로초 정수로 싣는다(created_at 마이크로초 정밀도 보존).
 * created_at 은 존 없는 LocalDateTime 이라 epoch 변환은 UTC 고정이다 — encode/decode 가 같은 존이면 대칭이라
 * 절대 시각 의미는 불필요하고, JVM 기본 존에 따라 값이 갈리는 것만 막는다. 디코드 실패(Base64 불량·필드 수/
 * 타입 불일치)는 IllegalArgumentException 으로, epoch 범위 밖 시각 성분은 DateTimeException 으로 표면화한다 —
 * 둘 다 RuntimeException 이라 서비스가 한 번에 잡아 EventErrorCode.INVALID_CURSOR 로 변환한다.
 */
public record EventVideoCursor(long locationId, LocalDateTime createdAt, long id) {

	private static final long MICROS_PER_SECOND = 1_000_000L;
	private static final long NANOS_PER_MICRO = 1_000L;

	/**
	 * PostgreSQL timestamp 저장 하한 (기원전 4713년 1월 1일 = ISO -4712-01-01T00:00). 이보다 앞선 시각을
	 * 쿼리에 바인딩하면 드라이버가 뭉개거나 PostgreSQL 이 "timestamp out of range" 로 거부해 공통 500 이
	 * 된다 — 그 시점은 디코드 try-catch 밖이라 400 으로 변환되지 못하므로 디코드 단계에서 막는다
	 * (MissionVideoCursor 와 같은 판정선). 상한은 검사하지 않는다 — 저장 상한을 마이크로초로 환산하면
	 * long 을 넘어 이 포맷에 실릴 수 없다(Long.MAX_VALUE 는 서기 294247년, 저장 범위 안이다).
	 */
	private static final LocalDateTime MIN_STORABLE = LocalDateTime.of(-4712, 1, 1, 0, 0);

	public static String encode(long locationId, LocalDateTime createdAt, long id) {
		long epochMicros = createdAt.toEpochSecond(ZoneOffset.UTC) * MICROS_PER_SECOND
			+ createdAt.getNano() / NANOS_PER_MICRO;
		String raw = locationId + ":" + epochMicros + ":" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static EventVideoCursor decode(String token) {
		byte[] decoded = Base64.getUrlDecoder().decode(token);
		String raw = new String(decoded, StandardCharsets.UTF_8);
		String[] parts = raw.split(":", -1);
		if (parts.length != 3) {
			throw new IllegalArgumentException("커서 필드 수가 3이 아닙니다: " + raw);
		}
		long locationId;
		long epochMicros;
		long id;
		try {
			locationId = Long.parseLong(parts[0]);
			epochMicros = Long.parseLong(parts[1]);
			id = Long.parseLong(parts[2]);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("커서 성분이 정수가 아닙니다: " + raw, e);
		}
		LocalDateTime createdAt = LocalDateTime.ofEpochSecond(
			Math.floorDiv(epochMicros, MICROS_PER_SECOND),
			(int) (Math.floorMod(epochMicros, MICROS_PER_SECOND) * NANOS_PER_MICRO),
			ZoneOffset.UTC);
		if (createdAt.isBefore(MIN_STORABLE)) {
			throw new IllegalArgumentException("커서 시각이 저장 가능 범위 밖입니다: " + createdAt);
		}
		return new EventVideoCursor(locationId, createdAt, id);
	}
}
