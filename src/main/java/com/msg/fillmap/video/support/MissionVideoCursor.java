package com.msg.fillmap.video.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 미션 영상 목록 keyset 커서 (MSG-390). 마지막으로 내려준 항목의 경계값 (missionId, recordedAt, id) 를
 * Base64URL("{missionId}:{recordedAtEpochMicros}:{id}") 로 감싼 opaque 토큰이다 — VideoCursor(MSG-237) 의
 * 형식·규칙을 그대로 물려받되 성분 수와 의미가 달라(격자 목록은 gridId·viewCount·createdAt 축) 별도
 * record 로 둔다. 성분을 더하거나 뜻을 바꾸면 이미 발급된 격자 목록 커서가 깨진다. missionId 성분은
 * <b>발급 미션 바인딩</b>이다 — 다른 미션 요청에 재사용된 커서는 경계값이 그 미션의 keyset 으로
 * 오적용돼 결과가 조용히 잘리므로, 서비스가 요청 missionId 와 대조해 거부한다.
 * 시각은 ISO 문자열이 아니라 epoch 마이크로초 정수로 싣는다(recorded_at 마이크로초 정밀도 보존).
 * recorded_at 은 존 없는 LocalDateTime 이라 epoch 변환은 UTC 고정이다 — encode/decode 가 같은 존이면
 * 대칭이라 절대 시각 의미는 불필요하고, JVM 기본 존에 따라 값이 갈리는 것만 막는다.
 * 디코드 실패(Base64 불량·성분 수/타입 불일치·저장 가능 범위 밖 시각)는 IllegalArgumentException,
 * 시각 복원 자체가 실패하는 극단값은 DateTimeException 으로 표면화한다 — 둘 다 RuntimeException 이라
 * 서비스가 한 번에 잡아 VideoErrorCode.INVALID_CURSOR 로 변환한다.
 */
public record MissionVideoCursor(long missionId, LocalDateTime recordedAt, long id) {

	private static final long MICROS_PER_SECOND = 1_000_000L;
	private static final long NANOS_PER_MICRO = 1_000L;

	/**
	 * PostgreSQL timestamp 저장 하한 (기원전 4713년 1월 1일 = ISO -4712-01-01T00:00, epoch -210863520000000000µs).
	 * 이보다 앞선 시각을 쿼리에 바인딩하면 드라이버가 -infinity 로 뭉개거나(경계값 오적용) PostgreSQL 이
	 * "timestamp out of range" 로 거부해 공통 500 이 된다 — 그 시점은 디코드 try-catch 밖이라 400 으로
	 * 변환되지 못한다. 그래서 디코드 단계에서 막는다. <b>상한은 검사하지 않는다</b> — 저장 상한
	 * (294276-12-31)을 마이크로초로 환산하면 9224318015999999999 라 long 을 넘어, 시각 성분이 long 인
	 * 이 포맷에는 상한 초과 값이 애초에 실릴 수 없다(Long.MAX_VALUE 는 서기 294247년, 저장 범위 안이다).
	 * 검사 방향이 하나뿐인 것은 "커서 수용 범위는 항상 발급 가능 범위 이상"이라는 원칙의 결과다.
	 */
	private static final LocalDateTime MIN_STORABLE = LocalDateTime.of(-4712, 1, 1, 0, 0);

	public static String encode(long missionId, LocalDateTime recordedAt, long id) {
		long epochMicros = recordedAt.toEpochSecond(ZoneOffset.UTC) * MICROS_PER_SECOND
			+ recordedAt.getNano() / NANOS_PER_MICRO;
		String raw = missionId + ":" + epochMicros + ":" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static MissionVideoCursor decode(String token) {
		byte[] decoded = Base64.getUrlDecoder().decode(token);
		String raw = new String(decoded, StandardCharsets.UTF_8);
		String[] parts = raw.split(":", -1);
		if (parts.length != 3) {
			throw new IllegalArgumentException("커서 필드 수가 3이 아닙니다: " + raw);
		}
		long missionId;
		long epochMicros;
		long id;
		try {
			missionId = Long.parseLong(parts[0]);
			epochMicros = Long.parseLong(parts[1]);
			id = Long.parseLong(parts[2]);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("커서 성분이 정수가 아닙니다: " + raw, e);
		}
		LocalDateTime recordedAt = LocalDateTime.ofEpochSecond(
			Math.floorDiv(epochMicros, MICROS_PER_SECOND),
			(int) (Math.floorMod(epochMicros, MICROS_PER_SECOND) * NANOS_PER_MICRO),
			ZoneOffset.UTC);
		if (recordedAt.isBefore(MIN_STORABLE)) {
			throw new IllegalArgumentException("커서 시각이 저장 가능 범위 밖입니다: " + recordedAt);
		}
		return new MissionVideoCursor(missionId, recordedAt, id);
	}
}
