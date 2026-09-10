package com.msg.fillmap.video.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 전역 영상 목록 keyset 커서 (MSG-237 §D2). 마지막으로 내려준 항목의 경계값 (gridId, viewCount, createdAt, id)
 * 를 Base64URL("{gridId}:{viewCount}:{createdAtEpochMicros}:{id}") 로 감싼 opaque 토큰이다 — GridCursor(MSG-90)
 * 패턴의 video 축 등가물로, FE 는 토큰을 그대로 되돌려주기만 한다. gridId 성분은 **발급 격자 바인딩**이다 —
 * 다른 격자 요청에 재사용된 커서는 경계값이 그 격자의 keyset 으로 오적용돼 결과가 조용히 잘리므로, 서비스가
 * 요청 gridId 와 대조해 거부한다(2026-07-28 Codex 교차 리뷰 P2). gridId("{y}_{x}")에 콜론이 없고 뒤 3성분이
 * 전부 정수라 첫 성분으로 둬도 split 모호성이 없다. 시각 성분은 ISO 문자열이 아니라 epoch 마이크로초 정수로
 * 싣는다(created_at 마이크로초 정밀도 보존). created_at 은 존 없는 LocalDateTime 이라 epoch 변환은 UTC
 * 고정이다 — encode/decode 가 같은 존이면 대칭이라 절대 시각 의미는 불필요하고, JVM 기본 존에 따라 값이
 * 갈리는 것만 막는다. 디코드 실패(Base64 불량·필드 수/타입 불일치)는 IllegalArgumentException 으로, epoch
 * 범위 밖 시각 성분은 DateTimeException 으로 표면화한다 — 둘 다 RuntimeException 이라 서비스가 한 번에 잡아
 * VideoErrorCode.INVALID_CURSOR 로 변환한다.
 */
public record VideoCursor(String gridId, long viewCount, LocalDateTime createdAt, long id) {

	private static final long MICROS_PER_SECOND = 1_000_000L;
	private static final long NANOS_PER_MICRO = 1_000L;

	public static String encode(String gridId, long viewCount, LocalDateTime createdAt, long id) {
		long epochMicros = createdAt.toEpochSecond(ZoneOffset.UTC) * MICROS_PER_SECOND
			+ createdAt.getNano() / NANOS_PER_MICRO;
		String raw = gridId + ":" + viewCount + ":" + epochMicros + ":" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static VideoCursor decode(String token) {
		byte[] decoded = Base64.getUrlDecoder().decode(token);
		String raw = new String(decoded, StandardCharsets.UTF_8);
		String[] parts = raw.split(":", -1);
		if (parts.length != 4) {
			throw new IllegalArgumentException("커서 필드 수가 4가 아닙니다: " + raw);
		}
		try {
			long viewCount = Long.parseLong(parts[1]);
			long epochMicros = Long.parseLong(parts[2]);
			long id = Long.parseLong(parts[3]);
			LocalDateTime createdAt = LocalDateTime.ofEpochSecond(
				Math.floorDiv(epochMicros, MICROS_PER_SECOND),
				(int) (Math.floorMod(epochMicros, MICROS_PER_SECOND) * NANOS_PER_MICRO),
				ZoneOffset.UTC);
			return new VideoCursor(parts[0], viewCount, createdAt, id);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("커서 성분이 정수가 아닙니다: " + raw, e);
		}
	}
}
