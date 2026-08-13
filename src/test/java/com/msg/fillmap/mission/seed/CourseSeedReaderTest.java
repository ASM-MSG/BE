package com.msg.fillmap.mission.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * courses-seed.json 파서·검증 (MSG-225 모듈 1, 순수 단위 · DB 무관). 산출물 결함(D5·D6·D7 위반)은
 * 축제 파서의 행 스킵과 달리 **즉시 예외**다 — 코스는 파이프라인 산출물이라 결함 = 산출물 재생성 대상.
 */
// 검증: FR-MISSION-09
@DisplayName("CourseSeedReader 산출물 파싱·검증 — 위반 즉시 예외")
class CourseSeedReaderTest {

	private final CourseSeedReader reader = new CourseSeedReader(new ObjectMapper());

	private List<CourseRecord> read(String json) {
		InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
		return reader.read(in);
	}

	/** seq 1..count 스팟 배열 — gridY 를 seq 만큼 증가시켜 격자를 구분한다. */
	private static String spots(int count) {
		return IntStream.rangeClosed(1, count)
			.mapToObj(seq -> """
				{"seq": %d, "gridId": "%d_11392", "name": "스팟%d", "method": "tourapi"}"""
				.formatted(seq, 16794 + seq, seq))
			.reduce((a, b) -> a + "," + b)
			.orElseThrow();
	}

	private static String course(String crsIdx, String name, String pathJson, String spotsJson) {
		return """
			{"crsIdx": "%s", "name": "%s", "path": %s, "spots": [%s]}"""
			.formatted(crsIdx, name, pathJson, spotsJson);
	}

	private static final String PATH = """
		{"type": "LineString", "coordinates": [[129.03597, 35.09656], [129.03642, 35.09721]]}""";

	private static String valid(String crsIdx, String name) {
		return course(crsIdx, name, PATH, spots(5));
	}

	@Test
	void 유효_산출물이_코스_레코드로_매핑된다() {
		List<CourseRecord> records = read("[" + course("T_CRS_MNG0000005118", "남파랑길 3코스", PATH, spots(6)) + "]");

		assertThat(records).hasSize(1);
		CourseRecord record = records.get(0);
		assertThat(record.crsIdx()).isEqualTo("T_CRS_MNG0000005118");
		assertThat(record.title()).isEqualTo("남파랑길 3코스");
		assertThat(record.pathJson()).contains("LineString").contains("129.03597");
		assertThat(record.spots()).hasSize(6)
			.extracting(CourseRecord.Spot::seq)
			.containsExactly(1, 2, 3, 4, 5, 6);
		assertThat(record.spots().get(0).gridId()).isEqualTo("16795_11392");
	}

	@Test
	void path가_LineString이_아니면_예외다() {
		String point = """
			{"type": "Point", "coordinates": [129.03597, 35.09656]}""";

		assertThatThrownBy(() -> read("[" + course("T_1", "코스", point, spots(5)) + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("LineString");
	}

	@Test
	void 좌표가_2점_미만이면_예외다() {
		String onePoint = """
			{"type": "LineString", "coordinates": [[129.03597, 35.09656]]}""";

		assertThatThrownBy(() -> read("[" + course("T_1", "코스", onePoint, spots(5)) + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("좌표");
	}

	@Test
	void 좌표_원소가_경도위도_숫자_쌍이_아니면_예외다() {
		// 대표 결함: 점 배열이 아니라 숫자 나열 — size 2 라 "2점 미만" 검사는 통과하므로 원소 검증이 격발해야 한다.
		String numberList = """
			{"type": "LineString", "coordinates": [129.03597, 35.09656]}""";
		// 혼입 결함: 정상 쌍 사이의 1원소 배열·문자열 원소.
		String onePointElement = """
			{"type": "LineString", "coordinates": [[129.03597, 35.09656], [129.03642]]}""";
		String stringElement = """
			{"type": "LineString", "coordinates": [[129.03597, 35.09656], ["129.03642", "35.09721"]]}""";

		for (String badPath : new String[] {numberList, onePointElement, stringElement}) {
			assertThatThrownBy(() -> read("[" + course("T_1", "코스", badPath, spots(5)) + "]"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("숫자 쌍");
		}
	}

	@Test
	void 스팟이_5개_미만이거나_8개_초과면_예외다() {
		assertThatThrownBy(() -> read("[" + course("T_1", "코스", PATH, spots(4)) + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("스팟");
		assertThatThrownBy(() -> read("[" + course("T_1", "코스", PATH, spots(9)) + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("스팟");
	}

	@Test
	void seq가_1부터_연속이_아니면_예외다() {
		// seq 3 이 빠진 1,2,4,5,6 — 정렬해도 연속이 아니다.
		String gapped = spots(6).replace("\"seq\": 3", "\"seq\": 30");

		assertThatThrownBy(() -> read("[" + course("T_1", "코스", PATH, gapped) + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("seq");
	}

	@Test
	void gridId_포맷_위반이면_예외다() {
		String malformed = spots(5).replace("16795_11392", "16795-11392");

		assertThatThrownBy(() -> read("[" + course("T_1", "코스", PATH, malformed) + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("gridId");
	}

	@Test
	void gridId가_정규형이_아니면_예외다() {
		// 선행 0 — 정규식(숫자 나열)은 통과하지만 GridEncoder.encode 정규형과 문자열 불일치 = 죽은 스팟.
		String nonCanonical = spots(5).replace("16795_11392", "016795_11392");

		assertThatThrownBy(() -> read("[" + course("T_1", "코스", PATH, nonCanonical) + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("정규형");
	}

	@Test
	void crsIdx나_name이_문자열이_아니면_예외다() {
		// 숫자 name — asString() 관용 변환이면 제목 "123" 으로 조용히 통과하던 입력.
		String numericName = """
			{"crsIdx": "T_1", "name": 123, "path": %s, "spots": [%s]}""".formatted(PATH, spots(5));

		assertThatThrownBy(() -> read("[" + numericName + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("문자열");
	}

	@Test
	void 코스_안에서_gridId가_중복이면_예외다() {
		// 스팟 2의 격자를 스팟 1과 같게 — PK 흡수로 실격자 수 < N 이 되는 산출물은 전량 거부한다 (D7).
		String duplicated = spots(5).replace("16796_11392", "16795_11392");

		assertThatThrownBy(() -> read("[" + course("T_1", "코스", PATH, duplicated) + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("gridId")
			.hasMessageContaining("중복");
	}

	@Test
	void crsIdx나_제목이_중복이면_예외다() {
		assertThatThrownBy(() -> read("[" + valid("T_1", "코스A") + "," + valid("T_1", "코스B") + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("중복");
		assertThatThrownBy(() -> read("[" + valid("T_1", "같은 코스") + "," + valid("T_2", "같은 코스") + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("중복");
	}

	@Test
	void 제목_200자_초과는_절단된다() {
		String longName = "가".repeat(250);

		List<CourseRecord> records = read("[" + valid("T_1", longName) + "]");

		assertThat(records.get(0).title()).hasSize(200);
	}

	/** 화면용 필드 5종(D4)을 원문 조각 그대로 얹은 코스 1건 — 결측·형식 위반 형태를 그대로 만든다. */
	private static String withMetadata(String contentsJson, String sigunJson, String distanceJson,
		String durationJson, String levelJson) {
		return """
			{"crsIdx": "T_META", "name": "메타데이터 코스", "path": %s, "spots": [%s], \
			"contents": %s, "sigun": %s, "distanceKm": %s, "durationMinutes": %s, "level": %s}"""
			.formatted(PATH, spots(5), contentsJson, sigunJson, distanceJson, durationJson, levelJson);
	}

	private CourseRecord readMetadata(String contentsJson, String sigunJson, String distanceJson,
		String durationJson, String levelJson) {
		return read("[" + withMetadata(contentsJson, sigunJson, distanceJson, durationJson, levelJson) + "]").get(0);
	}

	// 검증: FR-MISSION-16
	@Test
	void 코스_소개문의_br_태그가_개행으로_바뀐다() {
		String contents = "\"바다를 따라 걷는다<br>중간에 전망대가 있다<br/>종점은 흰여울마을이다<BR />끝\"";

		CourseRecord record = readMetadata(contents, "\"부산 영도구\"", "\"14\"", "\"330\"", "\"2\"");

		assertThat(record.description())
			.isEqualTo("바다를 따라 걷는다\n중간에 전망대가 있다\n종점은 흰여울마을이다\n끝")
			.doesNotContainIgnoringCase("<br");
		assertThat(record.placeName()).isEqualTo("부산 영도구");
	}

	// 검증: FR-MISSION-16
	@Test
	void 코스_거리_문자열_14는_14000미터로_환산된다() {
		CourseRecord record = readMetadata("\"소개\"", "\"부산 영도구\"", "\"14\"", "\"330\"", "\"2\"");

		assertThat(record.distanceMeters()).isEqualTo(14000);
		// crsTotlRqrmHour 는 이름과 달리 단위가 분이다 — "330" 이 5시간 30분 (D3).
		assertThat(record.durationMinutes()).isEqualTo(330);
		assertThat(record.difficulty()).isEqualTo(2);
	}

	// 검증: FR-MISSION-16
	@Test
	void 코스_거리에_소수점이_있어도_미터로_보존된다() {
		// 정수 컬럼에 킬로미터를 그대로 담으면 13 으로 잘린다 — 미터 환산이 그 손실을 막는다 (D2).
		assertThat(readMetadata("\"소개\"", "\"시군\"", "\"13.4\"", "\"330\"", "\"1\"").distanceMeters())
			.isEqualTo(13400);
		assertThat(readMetadata("\"소개\"", "\"시군\"", "13.45", "\"330\"", "\"1\"").distanceMeters())
			.isEqualTo(13450);
	}

	// 검증: FR-MISSION-16
	@Test
	void 코스_메타데이터_키가_없으면_null로_읽고_거부하지_않는다() {
		// 기존 산출물 파일(신규 키 없음)로 재실행해도 148 코스가 통째로 거부되지 않아야 한다 (D4).
		List<CourseRecord> records = read("[" + valid("T_1", "키 없는 코스") + "]");

		CourseRecord record = records.get(0);
		assertThat(record.description()).isNull();
		assertThat(record.placeName()).isNull();
		assertThat(record.distanceMeters()).isNull();
		assertThat(record.durationMinutes()).isNull();
		assertThat(record.difficulty()).isNull();
		// 판정에 쓰이는 값은 그대로 살아 있다 — 결측 허용은 화면 값에만 적용된다.
		assertThat(record.spots()).hasSize(5);
	}

	// 검증: FR-MISSION-16
	@Test
	void 코스_거리가_숫자가_아니면_예외를_던진다() {
		// 형식 위반은 결측이 아니라 결함이다 — 기존 전량 거부 계약 그대로 (D4).
		assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", "\"십사킬로\"", "\"330\"", "\"2\""))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("distanceKm");
		assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", "true", "\"330\"", "\"2\""))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("distanceKm");
		assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", "\"14\"", "\"330\"", "\"2.5\""))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("정수");
	}

	// 검증: FR-MISSION-16
	@Test
	void 코스_거리가_NaN이면_예외를_던진다() {
		// Double.parseDouble 은 "NaN"·"Infinity" 를 그대로 받는다 — 숫자 노드에만 유한성 검사를 걸면
		// 거리 0m(NaN 반올림)·소요시간 Integer.MAX_VALUE 로 조용히 적재된다 (Codex 리뷰 파생).
		for (String notFinite : new String[] {"\"NaN\"", "\"Infinity\"", "\"-Infinity\""}) {
			assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", notFinite, "\"330\"", "\"2\""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("유한 숫자");
			assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", "\"14\"", notFinite, "\"2\""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("유한 숫자");
		}
	}

	// 검증: FR-MISSION-16
	@Test
	void 코스_숫자가_INTEGER_범위를_넘으면_예외를_던진다() {
		// Double.intValue() 는 범위 초과를 Integer.MAX_VALUE 로 조용히 포화시킨다 — 거리는 미터 환산
		// 뒤(×1000) 범위를 넘으므로 3,000,000km 가 아니라 3,000,000 로도 터진다.
		assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", "\"3000000\"", "\"330\"", "\"2\""))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("INTEGER 범위");
		assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", "\"14\"", "\"3000000000\"", "\"2\""))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("INTEGER 범위");
		// 경계는 통과한다 — 2,147,483km 는 미터로 Integer.MAX_VALUE 미만이다.
		assertThat(readMetadata("\"소개\"", "\"시군\"", "\"2000000\"", "\"330\"", "\"2\"").distanceMeters())
			.isEqualTo(2_000_000_000);
	}

	// 검증: FR-MISSION-16
	@Test
	void 코스_난이도가_범위_밖이면_예외를_던진다() {
		// V31 이 값 범위 CHECK 를 안 건 근거가 "reader 가 한다"이다 (§D2) — 여기가 비면 양쪽 다 빈 상태가 된다.
		for (String outside : new String[] {"\"0\"", "\"4\"", "\"7\"", "\"-1\""}) {
			assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", "\"14\"", "\"330\"", outside))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("범위 밖");
		}
		// 1·2·3 은 통과한다 (148 코스 전수 실측값).
		for (String grade : new String[] {"\"1\"", "\"2\"", "\"3\""}) {
			assertThat(readMetadata("\"소개\"", "\"시군\"", "\"14\"", "\"330\"", grade).difficulty()).isNotNull();
		}
	}

	// 검증: FR-MISSION-16
	@Test
	void 코스_거리가_0이하면_예외를_던진다() {
		// 0 과 음수는 결측이 아니라 결함이다 — 화면에 "0분"·"-5km" 로 나간다 (§D2).
		for (String notPositive : new String[] {"\"0\"", "\"-5\"", "\"0.0004\""}) {
			assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", notPositive, "\"330\"", "\"2\""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("양수");
		}
		for (String notPositive : new String[] {"\"0\"", "\"-30\""}) {
			assertThatThrownBy(() -> readMetadata("\"소개\"", "\"시군\"", "\"14\"", notPositive, "\"2\""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("양수");
		}
	}
}
