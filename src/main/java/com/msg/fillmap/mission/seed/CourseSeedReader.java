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
 *
 * MSG-383 이 더한 화면용 필드 5종(contents·sigun·distanceKm·durationMinutes·level)만 예외 계약이
 * 다르다: **결측은 허용하고 형식 위반은 거부한다**(D4). 신규 필드에도 전량 거부를 적용하면 기존
 * 산출물 파일로 재실행하는 순간 148 코스가 통째로 거부돼 운영자가 손댈 수 없기 때문이다.
 * MSG-394 가 더한 imageKey 도 같은 계약이다 — 사진 있는 스팟이 없는 코스는 키가 아예 없다.
 */
@Component
public class CourseSeedReader {

	private static final int SPOTS_MIN = 5;
	private static final int SPOTS_MAX = 8;
	/** missions.title VARCHAR(200) 방어 절단 (MSG-224 미러) — dedupe 키도 절단값으로 일관. */
	private static final int TITLE_MAX_LENGTH = 200;
	/** 대표 이미지 키가 놓이는 버킷 경로 (MSG-394 D3 산출물 계약). 형식 검증은 SeedText 공용. */
	private static final String IMAGE_KEY_PREFIX = "missions/course/";
	/** 논리 식별자 "{grid_y}_{grid_x}" (glossary) — 음수 인덱스 허용. */
	private static final Pattern GRID_ID = Pattern.compile("-?\\d+_-?\\d+");
	/**
	 * 두루누비 난이도 등급 (148 코스 전수 실측: 1·2·3 뿐, 4 이상 0건). V31 이 값 범위 CHECK 를 걸지 않은
	 * 근거가 "그 검증은 시더의 reader 가 이미 하는 일"이라서다(MSG-383 §D2) — 등급이 하나 늘면 여기
	 * 상수만 바꾸면 되고 마이그레이션이 필요 없다.
	 */
	private static final int DIFFICULTY_MIN = 1;
	private static final int DIFFICULTY_MAX = 3;

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
		String crsIdx = requireText(course, "crsIdx");
		String name = requireText(course, "name");
		if (crsIdx.isBlank() || name.isBlank()) {
			throw new IllegalStateException("crsIdx/name 이 비어 있습니다: " + course);
		}
		validatePath(course.path("path"), crsIdx);
		return new CourseRecord(crsIdx, truncateTitle(name), course.path("path").toString(), toSpots(course, crsIdx),
			SeedText.normalizeBreaks(SeedText.text(course, "contents")),
			SeedText.truncatePlaceName(SeedText.text(course, "sigun")),
			distanceMeters(course, crsIdx),
			positive(integer(course, "durationMinutes", crsIdx), "durationMinutes", crsIdx),
			difficulty(course, crsIdx),
			SeedText.imageKey(course, IMAGE_KEY_PREFIX));
	}

	/**
	 * 두루누비 등급 1~3 밖은 거부한다 (MSG-383 §D2) — DB CHECK 를 안 건 근거가 "reader 가 한다"이므로
	 * 여기가 비면 {@code level: 7} 이 그대로 적재돼 화면에 정의 없는 난이도로 나간다.
	 */
	private static Integer difficulty(JsonNode course, String crsIdx) {
		Integer level = integer(course, "level", crsIdx);
		if (level != null && (level < DIFFICULTY_MIN || level > DIFFICULTY_MAX)) {
			throw new IllegalStateException("level 이 " + DIFFICULTY_MIN + "~" + DIFFICULTY_MAX
				+ " 범위 밖입니다 (D2): " + crsIdx + " = " + level);
		}
		return level;
	}

	/**
	 * 거리·소요시간은 값이 있으면 양수여야 한다 (MSG-383 §D2). 0 과 음수는 결측이 아니라 결함이다 —
	 * 화면에 "0분"·"-5km" 로 나가고, 거리 0 은 "아직 안 잰 코스"와 구분되지 않는다. 소요시간에도 같은
	 * 기준을 적용한 이유는 두루누비 148 코스 실측이 전건 양수이고, 둘 다 같은 화면 줄에 나란히 표시돼
	 * 한쪽만 방어하면 다른 쪽이 그대로 새기 때문이다.
	 */
	private static Integer positive(Integer value, String field, String crsIdx) {
		if (value != null && value <= 0) {
			throw new IllegalStateException(field + " 가 양수가 아닙니다 (D2): " + crsIdx + " = " + value);
		}
		return value;
	}

	/**
	 * 킬로미터 값을 미터로 환산한다 (MSG-383 D3) — 원본이 {@code "14"} 같은 문자열 정수라 지금은
	 * 소수점이 없지만, 재수집에서 {@code "13.4"} 가 들어오면 정수 컬럼이 조용히 13 으로 잘라낸다.
	 * 미터로 담으면 13,400 으로 보존된다. 표기 단위 변환은 화면 몫이다.
	 */
	private static Integer distanceMeters(JsonNode course, String crsIdx) {
		Double kilometers = decimal(course, "distanceKm", crsIdx);
		if (kilometers == null) {
			return null;
		}
		// 양수 판정은 환산 뒤 미터 값으로 한다 — 0.0004km 처럼 반올림하면 0m 가 되는 값도 같이 잡힌다.
		return positive(requireIntRange(Math.round(kilometers * 1000), "distanceKm", crsIdx), "distanceKm", crsIdx);
	}

	/**
	 * 화면용 숫자 추출 (MSG-383 D4) — 키가 없으면 null(결측 허용), 키가 있는데 숫자가 아니면 예외다.
	 * 결측을 전량 거부로 다루면 기존 산출물 파일로 재실행하는 순간 148 코스가 통째로 거부돼 운영자가
	 * 손댈 수 없다. 반대로 형식 위반은 결측이 아니라 결함이라 기존 계약(전량 거부) 그대로다.
	 *
	 * 유한성 검사는 숫자 노드와 문자열 노드 <b>양쪽</b>에 건다. {@code Double.parseDouble} 이 "NaN" ·
	 * "Infinity" 를 그대로 받아들여서, 문자열만 빠지면 {@code "distanceKm": "NaN"} 이 거리 0m 로,
	 * {@code "durationMinutes": "Infinity"} 가 Integer.MAX_VALUE 로 조용히 적재된다 (Codex 리뷰 파생).
	 */
	private static Double decimal(JsonNode course, String field, String crsIdx) {
		JsonNode value = course.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		double parsed;
		if (value.isNumber()) {
			parsed = value.asDouble();
		} else if (value.isTextual()) {
			try {
				parsed = Double.parseDouble(value.asString().trim());
			} catch (NumberFormatException e) {
				throw new IllegalStateException(field + " 가 숫자가 아닙니다 (D4): " + crsIdx + " = " + value, e);
			}
		} else {
			throw new IllegalStateException(field + " 가 숫자가 아닙니다 (D4): " + crsIdx + " = " + value);
		}
		if (!Double.isFinite(parsed)) {
			throw new IllegalStateException(field + " 가 유한 숫자가 아닙니다 (D4): " + crsIdx + " = " + value);
		}
		return parsed;
	}

	/**
	 * INTEGER 컬럼 범위 방어 — {@code Double.intValue()} 는 범위를 넘으면 Integer.MAX_VALUE 로 조용히
	 * 포화하고 {@code Math.toIntExact} 는 어느 코스의 어느 필드인지 모르는 ArithmeticException 을 던진다.
	 */
	private static int requireIntRange(double value, String field, String crsIdx) {
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			throw new IllegalStateException(field + " 가 INTEGER 범위를 벗어납니다 (D4): " + crsIdx + " = " + value);
		}
		return (int)value;
	}

	/** 정수 전용 — 소요시간(분)·난이도 등급은 소수점이 들어오면 결함이다 (D4). */
	private static Integer integer(JsonNode course, String field, String crsIdx) {
		Double value = decimal(course, field, crsIdx);
		if (value == null) {
			return null;
		}
		if (value != Math.rint(value)) {
			throw new IllegalStateException(field + " 가 정수가 아닙니다 (D4): " + crsIdx + " = " + value);
		}
		return requireIntRange(value, field, crsIdx);
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
			String gridId = requireText(spot, "gridId");
			if (!GRID_ID.matcher(gridId).matches()) {
				throw new IllegalStateException("gridId 포맷 위반입니다 (glossary 논리 식별자): " + crsIdx + " = " + gridId);
			}
			// 업로드 경로는 GridEncoder.encode 정규형("{y}_{x}")만 생성한다 — 선행 0·"-0" 같은 비정규형은
			// mission_grids 에 저장돼도 판정(grid_id 문자열 조인)에 영원히 안 잡히는 죽은 스팟이 된다.
			// GridEncoder 는 무수정(Owner A 유틸) — 정규형 왕복 검증은 reader 전담(D7).
			if (!isCanonicalGridId(gridId)) {
				throw new IllegalStateException("gridId 가 정규형이 아닙니다 (D7 죽은 스팟 방지): " + crsIdx + " = " + gridId);
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

	/** Long 왕복 재조립이 원본과 일치해야 정규형 — 정규식(자릿수 나열)만으로는 선행 0 을 못 거른다. */
	private static boolean isCanonicalGridId(String gridId) {
		int separator = gridId.indexOf('_');
		long gridY = Long.parseLong(gridId.substring(0, separator));
		long gridX = Long.parseLong(gridId.substring(separator + 1));
		return (gridY + "_" + gridX).equals(gridId);
	}

	/** isTextual 확인 후 추출 — asString() 의 관용 변환이 숫자/불리언을 조용히 문자열로 통과시키는 것 차단. */
	private static String requireText(JsonNode owner, String field) {
		JsonNode value = owner.path(field);
		if (!value.isTextual()) {
			throw new IllegalStateException(field + " 가 문자열이 아닙니다: " + value);
		}
		return value.asString();
	}

	private static String truncateTitle(String name) {
		return name.length() <= TITLE_MAX_LENGTH ? name : name.substring(0, TITLE_MAX_LENGTH);
	}
}
