package com.msg.fillmap.event.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 위치별 영상 피드 keyset 커서 (MSG-440 §API 2). 성분은 (locationId, createdAt, id) 3개고 와이어 포맷은
 * Base64URL("{locationId}:{createdAtEpochMicros}:{id}") 다. 여기서는 라운드트립 대칭·형식 위반 예외·
 * 시각 성분의 저장 가능 범위 판정을 본다 — 위치 바인딩 거부(13402)는 서비스 몫이라 피드 테스트가 본다.
 */
@DisplayName("EventVideoCursor 인코드·디코드")
class EventVideoCursorTest {

	private static final long LOCATION_ID = 77L;

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("인코딩한 커서를 디코드하면 원래 성분이 그대로다 (마이크로초 정밀도 포함)")
	void 인코딩한_커서를_디코드하면_원래_성분이_그대로다() {
		LocalDateTime createdAt = LocalDateTime.of(2026, 10, 6, 12, 30, 5, 123_456_000);

		String token = EventVideoCursor.encode(LOCATION_ID, createdAt, 1039L);

		assertThat(EventVideoCursor.decode(token))
			.isEqualTo(new EventVideoCursor(LOCATION_ID, createdAt, 1039L));
	}

	@Test
	@DisplayName("성분 수가 다른 토큰은 예외다")
	void 성분_수가_다른_토큰은_예외다() {
		assertThatThrownBy(() -> EventVideoCursor.decode(base64Url("77:1791290000000000")))
			.isInstanceOf(IllegalArgumentException.class);
		// 격자 목록의 4성분 커서(VideoCursor)도 이 커서로는 무효다 — 성분 수와 뜻이 다르다.
		assertThatThrownBy(() -> EventVideoCursor.decode(base64Url("19422_9582:5:1791290000000000:1039")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("정수가 아닌 성분과 깨진 Base64 는 예외다")
	void 정수가_아닌_성분과_깨진_Base64는_예외다() {
		assertThatThrownBy(() -> EventVideoCursor.decode(base64Url("77:a:1039")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> EventVideoCursor.decode("!!!not-base64!!!"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("저장 가능 범위를 벗어난 시각 성분은 예외다 — 쿼리 바인딩 단계 500 을 디코드에서 막는다")
	void 저장_가능_범위를_벗어난_시각_성분은_예외다() {
		assertThatThrownBy(() -> EventVideoCursor.decode(base64Url("77:" + Long.MIN_VALUE + ":1039")))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
