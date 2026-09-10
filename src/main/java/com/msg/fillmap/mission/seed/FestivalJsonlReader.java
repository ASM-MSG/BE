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

import com.msg.fillmap.global.geo.KoreaCoordinates;

/**
 * festivals.jsonl 파서 (MSG-224 D1 · MSG-384 D2·D6, 순수 로직 · DB 무관 — RegionGeoJsonReader 미러).
 * 1행 = JSON 1객체를 FestivalRecord 로 매핑하고, 날짜 누락/파싱 실패 · 종료 축제 · JSON 파싱 실패 ·
 * 필수 필드(이름·좌표) 위반 행은 건너뛰며 사유별로 집계한다(전량 실패 방지). "오늘" 판정은 todayKst
 * 주입 — 원본 날짜도 KST 달력 날짜라 양변이 같은 달력이다(D5).
 *
 * <b>대표 이미지 키만 관대한 skip 이 아니라 전량 거부다</b>(MSG-384 D2) — 형식 위반은 결측이 아니라
 * 보안 결함이라서다. 나머지 필드와 무게가 다르다. 단 검사 대상은 <b>활성 행</b>뿐이다 — 종료·필수 필드
 * 필터를 통과한 뒤에 보므로, 어차피 적재되지 않는 종료 축제 행의 키는 읽지 않는다.
 */
@Component
public class FestivalJsonlReader {

	/** 대표 이미지 키가 놓이는 버킷 경로 (MSG-384 D2 산출물 계약). 형식 검증은 SeedText 공용(MSG-394 D3). */
	private static final String IMAGE_KEY_PREFIX = "missions/festival/";

	private final ObjectMapper objectMapper;

	public FestivalJsonlReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public Result read(InputStream in, LocalDate todayKst) {
		List<FestivalRecord> records = new ArrayList<>();
		int invalidDate = 0;
		int ended = 0;
		int malformed = 0;
		int requiredField = 0;

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
			// 필수 필드 판정을 종료 필터 뒤에 두는 이유: 파일에 20년치 종료 축제가 들어 있고(D3), 운영자가
			// 파일을 셀 때 보는 것도 활성 행의 결측이다(운영 절차 2번의 jq 가 endDate 로 먼저 거른다).
			String name = SeedText.text(row, "name");
			double latitude = coordinate(row, "latitude");
			double longitude = coordinate(row, "longitude");
			if (name == null || KoreaCoordinates.isOutOfService(latitude, longitude)) {
				requiredField++;
				continue;
			}
			records.add(new FestivalRecord(
				name,
				latitude,
				longitude,
				startDate, endDate,
				SeedText.normalizeBreaks(SeedText.text(row, "description")),
				SeedText.truncatePlaceName(SeedText.text(row, "place")),
				SeedText.text(row, "homepage"),
				SeedText.imageKey(row, IMAGE_KEY_PREFIX)));
		}
		return new Result(records, invalidDate, ended, malformed, requiredField);
	}

	/**
	 * 좌표 추출 — 숫자 노드가 아니면 NaN 이다(= 서비스 범위 밖, D6). {@code asDouble()} 의 관용 변환에
	 * 맡기면 빈 문자열과 "abc" 가 0.0 이 돼 적도 앞바다에 미션이 하나 생긴다. 산출물 계약도 문자열
	 * 좌표를 계약 위반으로 세므로(운영 절차 2번 jq {@code type != "number"}) 판정을 일치시킨다.
	 */
	private static double coordinate(JsonNode row, String field) {
		JsonNode value = row.path(field);
		return value.isNumber() ? value.doubleValue() : Double.NaN;
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

	/**
	 * skippedRequiredField: 이름 결측·좌표 범위 밖으로 건너뛴 행 (MSG-384 D6). 사유를 이름과 좌표로
	 * 쪼개지 않는 것은 운영자가 할 일이 같아서다 — 어느 쪽이든 스크립트가 산출물 계약을 못 지킨 것이고
	 * 확인은 파일을 열어서 한다.
	 */
	public record Result(List<FestivalRecord> records, int skippedInvalidDate, int skippedEnded, int skippedMalformed,
		int skippedRequiredField) {
	}
}
