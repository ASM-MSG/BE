package com.msg.fillmap.usergrid.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CollectionGridCursor 인코드와 디코드")
class CollectionGridCursorTest {

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("행정동과 세 정렬값을 마이크로초 정밀도로 왕복한다")
	void 행정동과_세_정렬값을_마이크로초_정밀도로_왕복한다() {
		LocalDateTime uploadedAt = LocalDateTime.of(2026, 8, 22, 9, 30, 5, 123_456_000);

		String token = CollectionGridCursor.encode("1168051500", uploadedAt, 3, "19422_9582");

		assertThat(CollectionGridCursor.decode(token))
			.isEqualTo(new CollectionGridCursor("1168051500", uploadedAt, 3, "19422_9582"));
	}

	@Test
	@DisplayName("필드 수가 다르거나 Base64가 깨진 커서는 거절한다")
	void 필드_수가_다르거나_Base64가_깨진_커서는_거절한다() {
		assertThatThrownBy(() -> CollectionGridCursor.decode(base64Url("1168051500:1:3")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CollectionGridCursor.decode("!!!not-base64!!!"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("숫자가 아닌 시각과 영상 수는 거절한다")
	void 숫자가_아닌_시각과_영상_수는_거절한다() {
		assertThatThrownBy(() -> CollectionGridCursor.decode(base64Url("1168051500:a:3:19422_9582")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CollectionGridCursor.decode(base64Url("1168051500:1:a:19422_9582")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("음수 영상 수와 저장 가능 범위 밖 시각은 거절한다")
	void 음수_영상_수와_저장_가능_범위_밖_시각은_거절한다() {
		assertThatThrownBy(() -> CollectionGridCursor.decode(base64Url("1168051500:1:-1:19422_9582")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CollectionGridCursor.decode(
			base64Url("1168051500:" + Long.MIN_VALUE + ":3:19422_9582")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("형식이 아닌 gridId는 거절한다")
	void 형식이_아닌_gridId는_거절한다() {
		assertThatThrownBy(() -> CollectionGridCursor.decode(base64Url("1168051500:1:3:not-a-grid")))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
