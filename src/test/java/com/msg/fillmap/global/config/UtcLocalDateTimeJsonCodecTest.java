package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import tools.jackson.databind.ObjectMapper;

/**
 * 시각 필드의 와이어 표기 계약(MSG-376). 검증 대상은 손으로 만든 매퍼가 아니라 스프링이 조립한 매퍼다 —
 * {@code new ObjectMapper()} 는 {@code @JacksonComponent} 로 등록된 커스텀 처리기를 못 보기 때문에,
 * 그것으로 통과해도 실제 응답이 같다는 보장이 없다.
 */
@JsonTest
@DisplayName("UtcLocalDateTimeJsonCodec")
class UtcLocalDateTimeJsonCodecTest {

	@Autowired
	private ObjectMapper objectMapper;

	private record TimeHolder(LocalDateTime at) {
	}

	@Nested
	@DisplayName("직렬화")
	class Serialize {

		@Test
		void 시각_필드는_UTC_Z_표기로_직렬화된다() {
			String json = objectMapper.writeValueAsString(new TimeHolder(LocalDateTime.of(2026, 8, 11, 5, 55, 21)));

			assertThat(json).isEqualTo("{\"at\":\"2026-08-11T05:55:21Z\"}");
		}

		@Test
		void 초가_0인_시각도_초를_생략하지_않는다() {
			String json = objectMapper.writeValueAsString(new TimeHolder(LocalDateTime.of(2026, 8, 11, 5, 55, 0)));

			assertThat(json).isEqualTo("{\"at\":\"2026-08-11T05:55:00Z\"}");
		}

		@Test
		void 밀리초가_있는_시각은_소수_초를_유지한다() {
			String json = objectMapper.writeValueAsString(
				new TimeHolder(LocalDateTime.parse("2026-08-11T06:09:27.724")));

			assertThat(json).isEqualTo("{\"at\":\"2026-08-11T06:09:27.724Z\"}");
		}

		@Test
		void null_시각_필드는_null로_직렬화된다() {
			String json = objectMapper.writeValueAsString(new TimeHolder(null));

			assertThat(json).isEqualTo("{\"at\":null}");
		}
	}

	@Nested
	@DisplayName("역직렬화")
	class Deserialize {

		@Test
		void Z_표기_입력은_값_그대로_UTC로_해석된다() {
			TimeHolder holder = objectMapper.readValue("{\"at\":\"2026-08-11T06:09:27.724Z\"}", TimeHolder.class);

			assertThat(holder.at()).isEqualTo(LocalDateTime.parse("2026-08-11T06:09:27.724"));
		}

		@Test
		void 임의_오프셋_입력은_UTC로_환산된다() {
			TimeHolder holder = objectMapper.readValue("{\"at\":\"2026-08-11T14:55:21+09:00\"}", TimeHolder.class);

			assertThat(holder.at()).isEqualTo(LocalDateTime.of(2026, 8, 11, 5, 55, 21));
		}

		@Test
		void 표기_없는_입력은_UTC로_간주된다() {
			TimeHolder holder = objectMapper.readValue("{\"at\":\"2026-08-11T05:55:21\"}", TimeHolder.class);

			assertThat(holder.at()).isEqualTo(LocalDateTime.of(2026, 8, 11, 5, 55, 21));
		}

		@Test
		void 직렬화_후_역직렬화하면_원값이다() {
			LocalDateTime original = LocalDateTime.parse("2026-08-11T06:09:27.724");

			TimeHolder roundTrip = objectMapper.readValue(
				objectMapper.writeValueAsString(new TimeHolder(original)), TimeHolder.class);

			assertThat(roundTrip.at()).isEqualTo(original);
		}
	}
}
