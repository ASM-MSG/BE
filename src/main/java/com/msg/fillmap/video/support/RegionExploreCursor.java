package com.msg.fillmap.video.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Pattern;

/** 전체 지역 페이지의 사용자 바인딩 키셋 커서. */
public record RegionExploreCursor(
	long userId,
	LocalDateTime personalLastUploadedAt,
	int gridCount,
	String regionCode
) {

	private static final long MICROS_PER_SECOND = 1_000_000L;
	private static final int NANOS_PER_MICRO = 1_000;
	private static final Pattern REGION_CODE = Pattern.compile("\\d{10}");

	public RegionExploreCursor {
		if (userId <= 0 || gridCount <= 0 || !REGION_CODE.matcher(regionCode).matches()) {
			throw new IllegalArgumentException("커서 값이 올바르지 않습니다");
		}
	}

	public static String encode(
		long userId, LocalDateTime personalLastUploadedAt, int gridCount, String regionCode) {
		new RegionExploreCursor(userId, personalLastUploadedAt, gridCount, regionCode);
		String timestamp = personalLastUploadedAt == null ? "" : Long.toString(toEpochMicros(personalLastUploadedAt));
		String raw = userId + ":" + timestamp + ":" + gridCount + ":" + regionCode;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static RegionExploreCursor decode(String token) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
			String[] parts = raw.split(":", -1);
			if (parts.length != 4) {
				throw new IllegalArgumentException("커서 필드 수가 올바르지 않습니다");
			}
			long userId = Long.parseLong(parts[0]);
			LocalDateTime uploadedAt = parts[1].isEmpty() ? null : fromEpochMicros(Long.parseLong(parts[1]));
			int gridCount = Integer.parseInt(parts[2]);
			return new RegionExploreCursor(userId, uploadedAt, gridCount, parts[3]);
		} catch (RuntimeException e) {
			if (e instanceof IllegalArgumentException illegalArgumentException) {
				throw illegalArgumentException;
			}
			throw new IllegalArgumentException("유효하지 않은 전체 지역 커서입니다", e);
		}
	}

	private static long toEpochMicros(LocalDateTime value) {
		long seconds = value.toEpochSecond(ZoneOffset.UTC);
		return Math.addExact(Math.multiplyExact(seconds, MICROS_PER_SECOND), value.getNano() / NANOS_PER_MICRO);
	}

	private static LocalDateTime fromEpochMicros(long value) {
		return LocalDateTime.ofEpochSecond(
			Math.floorDiv(value, MICROS_PER_SECOND),
			(int) (Math.floorMod(value, MICROS_PER_SECOND) * NANOS_PER_MICRO),
			ZoneOffset.UTC);
	}
}
