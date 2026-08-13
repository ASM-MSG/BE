package com.msg.fillmap.mission.seed;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * festivals.jsonl 파서 (MSG-224 D1, 순수 로직 · DB 무관 — RegionGeoJsonReader 미러).
 * 1행 = JSON 1객체를 FestivalRecord 로 매핑하고, 날짜 누락/파싱 실패 · 종료 축제 · JSON 파싱 실패 행은
 * 건너뛰며 사유별로 집계한다(전량 실패 방지). "오늘" 판정은 todayKst 주입 — 원본 날짜도 KST 달력
 * 날짜라 양변이 같은 달력이다(D5). 위경도 재검증은 하지 않는다 — 병합 단계(merge_festivals.py) 보장 신뢰.
 */
@Component
public class FestivalJsonlReader {

	private final ObjectMapper objectMapper;

	public FestivalJsonlReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public Result read(InputStream in, LocalDate todayKst) {
		List<FestivalRecord> records = new ArrayList<>();
		int invalidDate = 0;
		int ended = 0;
		int malformed = 0;

		List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode row;
			try {
				row = objectMapper.readTree(line);
			} catch (JacksonException e) {
				malformed++;
				continue;
			}
			LocalDate startDate = date(row, "startDate");
			LocalDate endDate = date(row, "endDate");
			if (startDate == null || endDate == null) {
				invalidDate++;
				continue;
			}
			if (endDate.isBefore(todayKst)) {
				ended++;
				continue;
			}
			records.add(new FestivalRecord(
				row.path("name").asString(),
				row.path("latitude").asDouble(),
				row.path("longitude").asDouble(),
				startDate, endDate,
				SeedText.text(row, "description"),
				SeedText.truncatePlaceName(SeedText.text(row, "place")),
				SeedText.text(row, "homepage")));
		}
		return new Result(records, invalidDate, ended, malformed);
	}

	/** YYYY-MM-DD 파싱 — 누락·빈 문자열·파싱 실패는 null(행 제외 사유). EVENT 를 무기간으로 만들지 않는다(D1). */
	private static LocalDate date(JsonNode row, String field) {
		JsonNode value = row.path(field);
		if (value.isMissingNode() || value.isNull() || value.asString().isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(value.asString());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	public record Result(List<FestivalRecord> records, int skippedInvalidDate, int skippedEnded, int skippedMalformed) {
	}
}
