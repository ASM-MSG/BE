package com.msg.fillmap.global.config;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;

import org.springframework.boot.jackson.JacksonComponent;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * 모든 API 의 {@link LocalDateTime} 필드를 UTC 표기(끝에 {@code Z})로 주고받게 하는 전역 JSON 처리기 (MSG-376).
 *
 * <p>DB 와 엔티티는 시간대 정보가 없는 시각(naive)을 UTC 로 저장·해석한다. 그 값이 표기 없이 그대로 나가면
 * 브라우저의 {@code new Date()} 가 자기 시간대(한국이면 KST)로 읽어 9시간 밀려 보인다. 그래서 나갈 때는
 * "이 값은 UTC"라는 표기만 붙이고(값 변환 없음), 들어올 때는 어떤 오프셋으로 오든 UTC 로 환산해 받는다.
 *
 * <p>스프링 부트가 앱 전체 JSON 매퍼에 자동 등록한다({@code @JacksonComponent} — 부트 4 에서 {@code @JsonComponent}
 * 가 개명된 이름). 필드마다 어노테이션을 붙이지 않고 이 한 곳만 두는 이유는, 신규 DTO 가 {@code LocalDateTime}
 * 을 쓰는 순간 별도 조치 없이 같은 계약을 따르게 하기 위해서다.
 */
@JacksonComponent
public class UtcLocalDateTimeJsonCodec {

	/** {@code ISO_LOCAL_DATE_TIME} 뒤 오프셋({@code Z}, {@code +09:00})은 있어도 되고 없어도 된다. */
	private static final DateTimeFormatter OPTIONAL_OFFSET = new DateTimeFormatterBuilder()
		.append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
		.optionalStart()
		.appendOffsetId()
		.optionalEnd()
		.toFormatter();

	public static class Serializer extends ValueSerializer<LocalDateTime> {

		@Override
		public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt)
			throws JacksonException {
			// 저장값이 이미 UTC 라 표기만 붙는다 — 절대 시각은 이동하지 않는다.
			// Instant.toString 은 초를 항상 출력하고 소수 초는 있을 때만 붙인다 (자바 12+).
			gen.writeString(value.toInstant(ZoneOffset.UTC).toString());
		}
	}

	public static class Deserializer extends ValueDeserializer<LocalDateTime> {

		@Override
		public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
			String text = p.getValueAsString();
			if (text == null) {
				// 객체·배열처럼 문자열로 읽을 수 없는 토큰 — 여기서 NPE 로 500 이 되지 않게 Jackson 예외로 넘긴다.
				return (LocalDateTime)ctxt.handleUnexpectedToken(LocalDateTime.class, p);
			}
			try {
				TemporalAccessor parsed = OPTIONAL_OFFSET.parseBest(
					text.trim(), OffsetDateTime::from, LocalDateTime::from);
				if (parsed instanceof OffsetDateTime offsetDateTime) {
					return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
				}
				return (LocalDateTime)parsed;
			} catch (DateTimeParseException e) {
				// 형식 오류는 Jackson 표준 예외로 바꿔 기존 말폼드 본문 처리 경로(400)를 그대로 타게 한다.
				return (LocalDateTime)ctxt.handleWeirdStringValue(
					LocalDateTime.class, text, "ISO 8601 시각 형식이 아닙니다");
			}
		}
	}
}
