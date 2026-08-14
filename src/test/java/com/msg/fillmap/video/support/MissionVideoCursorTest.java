package com.msg.fillmap.video.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 미션 영상 목록 keyset 커서 (MSG-390). 성분은 (missionId, recordedAt, id) 3개이고 와이어 포맷은
 * Base64URL("{missionId}:{recordedAtEpochMicros}:{id}") 다 — VideoCursor(MSG-237) 의 형식·규칙을 그대로
 * 물려받되 성분 수와 뜻이 달라 별도 타입이다. 여기서는 라운드트립 대칭·형식 위반 예외·시각 성분의
 * 저장 가능 범위 판정을 본다.
 */
@DisplayName("MissionVideoCursor 인코드·디코드")
class MissionVideoCursorTest {

	private static final long MISSION_ID = 12L;

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("인코딩한 커서를 디코드하면 원래 성분이 그대로다 (마이크로초 정밀도 포함)")
	void 인코딩한_커서를_디코드하면_원래_성분이_그대로다() {
		// 마이크로초 자리(123456µs)가 살아 돌아오는지 — 시각을 정수 마이크로초로 싣는 이유가 이 정밀도다.
		LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 20, 18, 3, 11, 123_456_000);

		String token = MissionVideoCursor.encode(MISSION_ID, recordedAt, 1039L);

		assertThat(MissionVideoCursor.decode(token))
			.isEqualTo(new MissionVideoCursor(MISSION_ID, recordedAt, 1039L));
	}

	@Test
	@DisplayName("성분 수가 다른 토큰은 예외다")
	void 성분_수가_다른_토큰은_예외다() {
		assertThatThrownBy(() -> MissionVideoCursor.decode(base64Url("12:1784455800000000")))
			.isInstanceOf(IllegalArgumentException.class);
		// 격자 목록의 4성분 커서(VideoCursor)도 이 커서로는 무효다 — 성분 수와 뜻이 다르다.
		assertThatThrownBy(() -> MissionVideoCursor.decode(base64Url("19422_9582:5:1784455800000000:1039")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("정수가 아닌 성분은 예외다")
	void 정수가_아닌_성분은_예외다() {
		assertThatThrownBy(() -> MissionVideoCursor.decode(base64Url("12:a:1039")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MissionVideoCursor.decode(base64Url("mission:1784455800000000:1039")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MissionVideoCursor.decode("!!!not-base64!!!"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("저장 가능 범위를 벗어난 시각 성분은 예외다 — 쿼리 바인딩 단계 500 을 디코드에서 막는다")
	void 저장_가능_범위를_벗어난_시각_성분은_예외다() {
		// Long.MIN_VALUE µs = ISO 연도 -290308. LocalDateTime 은 받아들이지만 PostgreSQL timestamp 는 못 담아
		// 바인딩 단계에서 깨진다(실측: setTimestamp → PSQLException "timestamp out of range").
		assertThatThrownBy(() -> MissionVideoCursor.decode(base64Url("12:" + Long.MIN_VALUE + ":1039")))
			.isInstanceOf(IllegalArgumentException.class);
		// 저장 하한(ISO 연도 -4712) 바로 앞 해도 같다 — 판정선은 PostgreSQL 저장 가능 범위다.
		long justBeforeMin = LocalDateTime.of(-4713, 12, 31, 23, 59, 59).toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
		assertThatThrownBy(() -> MissionVideoCursor.decode(base64Url("12:" + justBeforeMin + ":1039")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("저장 가능 범위의 양끝 시각은 인코드와 디코드를 왕복한다 (수용 범위가 경계에서 닫히지 않는다)")
	void 저장_가능_범위의_양끝_시각은_인코드와_디코드를_왕복한다() {
		// 하한 = PostgreSQL 저장 하한 그 값(기원전 4713년 1월 1일 = ISO 연도 -4712).
		LocalDateTime storableMin = LocalDateTime.of(-4712, 1, 1, 0, 0);
		// 상한 = 마이크로초 정수로 실을 수 있는 가장 큰 시각(ISO 연도 294247). PostgreSQL 저장 상한(294276년)은
		// epoch 마이크로초가 long 을 넘어 와이어 포맷 자체에 실리지 않으므로, 실제 상한은 이 값이다.
		LocalDateTime encodableMax = LocalDateTime.of(294247, 1, 10, 4, 0, 54, 775_807_000);

		for (LocalDateTime edge : new LocalDateTime[] {storableMin, encodableMax}) {
			String token = MissionVideoCursor.encode(MISSION_ID, edge, 1L);
			assertThat(MissionVideoCursor.decode(token).recordedAt()).isEqualTo(edge);
		}
		// 상한 끝의 원문 성분이 실제로 long 최대값이다 — 수용 범위가 발급 가능 범위 위로 열려 있음을 고정한다.
		assertThat(MissionVideoCursor.decode(base64Url("12:" + Long.MAX_VALUE + ":1")).recordedAt())
			.isEqualTo(encodableMax);
	}
}
