package com.msg.fillmap.mission.seed;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * courses-seed.json 파서·검증 (MSG-225 D6·D7, 순수 로직 · DB 무관 — FestivalJsonlReader 미러).
 * 축제 파서의 행 스킵·사유 집계와 달리 **위반 즉시 예외**다 — 코스는 파이프라인 완성 산출물이라
 * 결함 하나 = 산출물 재생성 대상이지 부분 적재 대상이 아니다(전체 롤백, MSG-224 FR-5 원칙 승계).
 */
@Component
public class CourseSeedReader {

	private static final int SPOTS_MIN = 5;
	private static final int SPOTS_MAX = 8;
	/** missions.title VARCHAR(200) 방어 절단 (MSG-224 미러) — dedupe 키도 절단값으로 일관. */
	private static final int TITLE_MAX_LENGTH = 200;
	/** 논리 식별자 "{grid_y}_{grid_x}" (glossary) — 음수 인덱스 허용. */
	private static final Pattern GRID_ID = Pattern.compile("-?\\d+_-?\\d+");

	private final ObjectMapper objectMapper;

	public CourseSeedReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public List<CourseRecord> read(InputStream in) {
		JsonNode root;
		try {
			root = objectMapper.readTree(in);
		} catch (JacksonException e) {
			throw new IllegalStateException("courses-seed.json 파싱에 실패했습니다", e);
		}
		if (!root.isArray()) {
			throw new IllegalStateException("courses-seed.json 루트는 코스 배열이어야 합니다");
		}

		List<CourseRecord> records = new ArrayList<>();
		Set<String> seenCrsIdx = new HashSet<>();
		Set<String> seenTitles = new HashSet<>();
		for (JsonNode course : root) {
			CourseRecord record = toRecord(course);
			if (!seenCrsIdx.add(record.crsIdx()) || !seenTitles.add(record.title())) {
				throw new IllegalStateException(
					"crsIdx/제목이 중복입니다 (dedupe 키 보전 위반, D6): " + record.crsIdx() + " / " + record.title());
			}
			records.add(record);
		}
		return records;
	}

	private CourseRecord toRecord(JsonNode course) {
		String crsIdx = course.path("crsIdx").asString();
		String name = course.path("name").asString();
		if (crsIdx.isBlank() || name.isBlank()) {
			throw new IllegalStateException("crsIdx/name 이 비어 있습니다: " + course);
		}
		validatePath(course.path("path"), crsIdx);
		return new CourseRecord(crsIdx, truncateTitle(name), course.path("path").toString(), toSpots(course, crsIdx));
	}

	private static void validatePath(JsonNode path, String crsIdx) {
		if (!"LineString".equals(path.path("type").asString())) {
			throw new IllegalStateException("path 가 GeoJSON LineString 이 아닙니다 (D5): " + crsIdx);
		}
		JsonNode coordinates = path.path("coordinates");
		if (!coordinates.isArray() || coordinates.size() < 2) {
			throw new IllegalStateException("path 좌표가 2점 미만입니다 (D5): " + crsIdx);
		}
		// 각 원소 = [lon, lat] 유한 숫자 쌍 (D5·D6 산출물 계약 — 고도 3원소도 거부). path 는 원문
		// passthrough(@JsonRawValue) 라 깨진 GeoJSON 이 FE 까지 그대로 나간다 — 여기서 전량 거부한다(D7).
		for (int i = 0; i < coordinates.size(); i++) {
			JsonNode point = coordinates.get(i);
			if (!point.isArray() || point.size() != 2
				|| !isFiniteNumber(point.get(0)) || !isFiniteNumber(point.get(1))) {
				throw new IllegalStateException(
					"path 좌표 원소가 [lon, lat] 숫자 쌍이 아닙니다 (D5): " + crsIdx + " coordinates[" + i + "] = " + point);
			}
		}
	}

	private static boolean isFiniteNumber(JsonNode value) {
		return value.isNumber() && Double.isFinite(value.asDouble());
	}

	private static List<CourseRecord.Spot> toSpots(JsonNode course, String crsIdx) {
		JsonNode spotsNode = course.path("spots");
		if (!spotsNode.isArray() || spotsNode.size() < SPOTS_MIN || spotsNode.size() > SPOTS_MAX) {
			throw new IllegalStateException(
				"스팟은 " + SPOTS_MIN + "~" + SPOTS_MAX + "개여야 합니다 (FR-6): " + crsIdx + " = " + spotsNode.size());
		}
		List<CourseRecord.Spot> spots = new ArrayList<>(spotsNode.size());
		Set<String> seenGridIds = new HashSet<>();
		for (JsonNode spot : spotsNode) {
			String gridId = spot.path("gridId").asString();
			if (!GRID_ID.matcher(gridId).matches()) {
				throw new IllegalStateException("gridId 포맷 위반입니다 (glossary 논리 식별자): " + crsIdx + " = " + gridId);
			}
			if (!seenGridIds.add(gridId)) {
				// mission_grids PK(mission_id, grid_id)가 중복 행을 조용히 흡수해 스팟 수 < N 이 되면
				// target_count 달성 불가 미션이 된다 — 파이프라인 격자 dedupe(D4-3) 위반 = 전량 거부(D7).
				throw new IllegalStateException("코스 안에서 스팟 gridId 가 중복입니다 (D7): " + crsIdx + " = " + gridId);
			}
			spots.add(new CourseRecord.Spot(spot.path("seq").asInt(), gridId));
		}
		spots.sort(Comparator.comparingInt(CourseRecord.Spot::seq));
		for (int i = 0; i < spots.size(); i++) {
			if (spots.get(i).seq() != i + 1) {
				throw new IllegalStateException("스팟 seq 가 1..N 연속이 아닙니다 (D6): " + crsIdx);
			}
		}
		return List.copyOf(spots);
	}

	private static String truncateTitle(String name) {
		return name.length() <= TITLE_MAX_LENGTH ? name : name.substring(0, TITLE_MAX_LENGTH);
	}
}
