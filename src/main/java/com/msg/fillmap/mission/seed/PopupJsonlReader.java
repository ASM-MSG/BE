package com.msg.fillmap.mission.seed;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * popups.jsonl 파서·검증 (MSG-235 D1·D6, 순수 로직 · DB 무관 — 입력 형식은 FestivalJsonlReader ×
 * 검증 계약은 CourseSeedReader). 축제 파서의 행 스킵·집계와 달리 **위반 즉시 예외**다 — 팝가 크롤 완성
 * 산출물이라 결함 하나 = 재크롤 대상이지 부분 적재 대상이 아니다(FR-6). 종료 필터(closeDate &lt; todayKst)만
 * 검증이 아니라 skip+집계 — 산출물에 종료분이 정상 포함된다(FR-1). periodType 은 읽지도 검증하지도 않는다 —
 * 주 1회 스냅샷이라 최대 7일 낡지만 날짜는 낡지 않고, 안 읽는 필드의 형식 강제는 크롤러 변경마다 적재를 깬다(D6).
 */
@Component
public class PopupJsonlReader {

	/** 한국 좌표 범위 (경계 포함, FR-6) — 좌표는 크롤 산출이라 축제("병합 단계 보장 신뢰")와 달리 reader 가 재검증한다. */
	private static final double LAT_MIN = 33.0;
	private static final double LAT_MAX = 39.0;
	private static final double LON_MIN = 124.0;
	private static final double LON_MAX = 132.0;
	/** missions.title VARCHAR(200) 방어 절단 (MSG-224 미러) — 검증이 아니라 방어다(D6). */
	private static final int NAME_MAX_LENGTH = 200;

	private final ObjectMapper objectMapper;

	public PopupJsonlReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public Result read(InputStream in, LocalDate todayKst) {
		List<PopupRecord> records = new ArrayList<>();
		Set<Long> seenIds = new HashSet<>();
		int ended = 0;

		List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			PopupRecord record = toRecord(parse(line));
			// 종료분과의 중복도 결함이다 — 크롤러가 id 유일을 보장하므로 검증은 필터보다 먼저다 (D3·D6).
			if (!seenIds.add(record.id())) {
				throw new IllegalStateException("팝가 id 가 파일 내에서 중복입니다 (멱등 키 보전 위반, D3): " + record.id());
			}
			if (record.closeDate().isBefore(todayKst)) {
				ended++;
				continue;
			}
			records.add(record);
		}
		return new Result(records, ended);
	}

	private JsonNode parse(String line) {
		try {
			return objectMapper.readTree(line);
		} catch (JacksonException e) {
			throw new IllegalStateException("popups.jsonl 행 JSON 파싱에 실패했습니다 (전량 거부, D6): " + line, e);
		}
	}

	private static PopupRecord toRecord(JsonNode row) {
		long id = requireIntegralId(row);
		String name = requireName(row, id);
		double latitude = requireCoordinate(row, "latitude", LAT_MIN, LAT_MAX, id);
		double longitude = requireCoordinate(row, "longitude", LON_MIN, LON_MAX, id);
		LocalDate openDate = requireDate(row, "openDate", id);
		LocalDate closeDate = requireDate(row, "closeDate", id);
		if (openDate.isAfter(closeDate)) {
			throw new IllegalStateException("openDate 가 closeDate 보다 늦습니다 (날짜 역전, FR-6): id " + id);
		}
		return new PopupRecord(id, truncateName(name), latitude, longitude, openDate, closeDate);
	}

	/** JSON 정수만 허용 — 문자열 "8151" 등의 관용 변환 차단(코스 requireText 원칙, D6). */
	private static long requireIntegralId(JsonNode row) {
		JsonNode value = row.path("id");
		if (!value.isIntegralNumber()) {
			throw new IllegalStateException("id 가 JSON 정수가 아닙니다 (FR-6): " + value);
		}
		return value.longValue();
	}

	private static String requireName(JsonNode row, long id) {
		JsonNode value = row.path("name");
		if (!value.isTextual() || value.asString().isBlank()) {
			throw new IllegalStateException("name 이 비공백 문자열이 아닙니다 (FR-6): id " + id + " = " + value);
		}
		return value.asString();
	}

	private static double requireCoordinate(JsonNode row, String field, double min, double max, long id) {
		JsonNode value = row.path(field);
		if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
			throw new IllegalStateException(field + " 가 유한 숫자가 아닙니다 (FR-6): id " + id + " = " + value);
		}
		double coordinate = value.asDouble();
		if (coordinate < min || coordinate > max) {
			throw new IllegalStateException(
				field + " 가 한국 범위(" + min + "~" + max + ") 밖입니다 (FR-6): id " + id + " = " + coordinate);
		}
		return coordinate;
	}

	private static LocalDate requireDate(JsonNode row, String field, long id) {
		JsonNode value = row.path(field);
		if (!value.isTextual()) {
			throw new IllegalStateException(field + " 가 문자열이 아닙니다 (FR-6): id " + id + " = " + value);
		}
		try {
			return LocalDate.parse(value.asString());
		} catch (DateTimeParseException e) {
			throw new IllegalStateException(
				field + " 가 ISO 날짜(YYYY-MM-DD)가 아닙니다 (FR-6): id " + id + " = " + value, e);
		}
	}

	private static String truncateName(String name) {
		return name.length() <= NAME_MAX_LENGTH ? name : name.substring(0, NAME_MAX_LENGTH);
	}

	public record Result(List<PopupRecord> records, int skippedEnded) {
	}
}
