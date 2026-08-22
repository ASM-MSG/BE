package com.msg.fillmap.usergrid.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import com.msg.fillmap.grid.GridEncoder;

/**
 * 행정동 전체 보기 개인 격자의 keyset 커서.
 * 행정동 바인딩과 마지막 카드의 정렬값을 Base64URL로 감싼다.
 */
public record CollectionGridCursor(
	String regionCode,
	LocalDateTime lastUploadedAt,
	int videoCount,
	String gridId
) {

	private static final long MICROS_PER_SECOND = 1_000_000L;
	private static final long NANOS_PER_MICRO = 1_000L;
	private static final LocalDateTime MIN_STORABLE = LocalDateTime.of(-4712, 1, 1, 0, 0);

	public static String encode(String regionCode, LocalDateTime lastUploadedAt, int videoCount, String gridId) {
		long epochMicros = Math.addExact(
			Math.multiplyExact(lastUploadedAt.toEpochSecond(ZoneOffset.UTC), MICROS_PER_SECOND),
			lastUploadedAt.getNano() / NANOS_PER_MICRO);
		String raw = regionCode + ":" + epochMicros + ":" + videoCount + ":" + gridId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static CollectionGridCursor decode(String token) {
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(token);
			String raw = new String(decoded, StandardCharsets.UTF_8);
			String[] parts = raw.split(":", -1);
			if (parts.length != 4 || parts[0].isBlank()) {
				throw new IllegalArgumentException("커서 필드가 올바르지 않습니다");
			}
			long epochMicros = Long.parseLong(parts[1]);
			int videoCount = Integer.parseInt(parts[2]);
			if (videoCount < 0) {
				throw new IllegalArgumentException("커서 영상 수가 음수입니다");
			}
			LocalDateTime lastUploadedAt = LocalDateTime.ofEpochSecond(
				Math.floorDiv(epochMicros, MICROS_PER_SECOND),
				(int) (Math.floorMod(epochMicros, MICROS_PER_SECOND) * NANOS_PER_MICRO),
				ZoneOffset.UTC);
			if (lastUploadedAt.isBefore(MIN_STORABLE)) {
				throw new IllegalArgumentException("커서 시각이 저장 가능 범위 밖입니다");
			}
			GridEncoder.decode(parts[3]);
			return new CollectionGridCursor(parts[0], lastUploadedAt, videoCount, parts[3]);
		} catch (RuntimeException e) {
			if (e instanceof IllegalArgumentException illegalArgumentException) {
				throw illegalArgumentException;
			}
			throw new IllegalArgumentException("유효하지 않은 격자 커서입니다", e);
		}
	}
}
