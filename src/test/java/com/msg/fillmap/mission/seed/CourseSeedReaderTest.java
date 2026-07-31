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
				{"seq": %d, "gridId": "%d_112198", "name": "스팟%d", "method": "tourapi"}"""
				.formatted(seq, 39000 + seq, seq))
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
		assertThat(record.spots().get(0).gridId()).isEqualTo("39001_112198");
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
		String malformed = spots(5).replace("39001_112198", "39001-112198");

		assertThatThrownBy(() -> read("[" + course("T_1", "코스", PATH, malformed) + "]"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("gridId");
	}

	@Test
	void 코스_안에서_gridId가_중복이면_예외다() {
		// 스팟 2의 격자를 스팟 1과 같게 — PK 흡수로 실격자 수 < N 이 되는 산출물은 전량 거부한다 (D7).
		String duplicated = spots(5).replace("39002_112198", "39001_112198");

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
}
