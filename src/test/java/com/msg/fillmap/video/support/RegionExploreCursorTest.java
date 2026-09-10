package com.msg.fillmap.video.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegionExploreCursor")
class RegionExploreCursorTest {

	@Test
	// 검증: FR-SEARCH-15
	@DisplayName("개인 지역 커서는 사용자와 정렬값을 마이크로초 정밀도로 왕복한다")
	void 개인_지역_커서는_사용자와_정렬값을_마이크로초_정밀도로_왕복한다() {
		LocalDateTime uploadedAt = LocalDateTime.of(2026, 8, 22, 9, 30, 5, 123_456_000);

		String token = RegionExploreCursor.encode(42L, uploadedAt, 1302, "5011062000");

		assertThat(RegionExploreCursor.decode(token))
			.isEqualTo(new RegionExploreCursor(42L, uploadedAt, 1302, "5011062000"));
	}

	@Test
	@DisplayName("비개인 지역 커서는 빈 업로드 시각을 왕복한다")
	void 비개인_지역_커서는_빈_업로드_시각을_왕복한다() {
		String token = RegionExploreCursor.encode(42L, null, 1302, "5011062000");

		assertThat(RegionExploreCursor.decode(token).personalLastUploadedAt()).isNull();
	}

	@Test
	@DisplayName("깨진 값과 유효하지 않은 범위는 거절한다")
	void 깨진_값과_유효하지_않은_범위는_거절한다() {
		assertThatThrownBy(() -> RegionExploreCursor.decode("not-a-cursor"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RegionExploreCursor.encode(0L, null, 0, "bad"))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
