package com.msg.fillmap.mission.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * festivals.jsonl 파서 검증 (MSG-224 모듈 1, 순수 단위 · DB 무관). 행 필터 4종(날짜 누락/파싱 실패 ·
 * 종료 축제 · JSON 파싱 실패 · 필수 필드 오류)과 사유별 집계, "오늘(KST)" 주입 경계를 본다 (D1).
 */
// 검증: FR-MISSION-08
@DisplayName("FestivalJsonlReader 행 파싱·필터·사유별 집계")
class FestivalJsonlReaderTest {

	private static final LocalDate TODAY_KST = LocalDate.of(2026, 7, 15);

	private final FestivalJsonlReader reader = new FestivalJsonlReader(new ObjectMapper());

	private FestivalJsonlReader.Result read(String... lines) {
		InputStream in = new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		return reader.read(in, TODAY_KST);
	}

	/** 실측 스키마(D1) 형태의 1행 — 미적재 필드(referenceDate·sourceOrg)도 원본처럼 포함한다. */
	private static String row(String name, String startDate, String endDate) {
		return """
			{"name": "%s", "place": "행사장 일원", "startDate": "%s", "endDate": "%s", \
			"description": "개막행사 등", "latitude": 37.5665, "longitude": 126.978, \
			"referenceDate": "2026-07-23", "homepage": "", "sourceOrg": "서울특별시_문화축제"}"""
			.formatted(name, startDate, endDate);
	}

	/** 화면용 필드(D3)를 임의 값으로 바꾼 1행 — 원문 조각 그대로 받아 결측·타입 위반 형태를 만든다. */
	private static String metadataRow(String descriptionJson, String placeJson, String homepageJson) {
		return """
			{"name": "메타데이터 축제", "startDate": "2026-07-10", "endDate": "2026-07-20", \
			"latitude": 37.5665, "longitude": 126.978, \
			"description": %s, "place": %s, "homepage": %s}"""
			.formatted(descriptionJson, placeJson, homepageJson);
	}

	/** 대표 이미지 키(MSG-384 D2)만 원문 조각 그대로 받는 1행. */
	private static String imageRow(String imageKeyJson) {
		return """
			{"name": "이미지 축제", "startDate": "2026-07-10", "endDate": "2026-07-20", \
			"latitude": 37.5665, "longitude": 126.978, "imageKey": %s}"""
			.formatted(imageKeyJson);
	}

	/** 필수 필드(이름·좌표, MSG-384 D6)를 원문 조각 그대로 받는 1행. */
	private static String requiredFieldRow(String nameJson, String latitudeJson, String longitudeJson) {
		return """
			{"name": %s, "startDate": "2026-07-10", "endDate": "2026-07-20", \
			"latitude": %s, "longitude": %s}"""
			.formatted(nameJson, latitudeJson, longitudeJson);
	}

	@Test
	void 유효_행이_레코드로_매핑된다() {
		FestivalJsonlReader.Result result = read(row("한강 여름 축제", "2026-07-10", "2026-07-20"));

		assertThat(result.records()).hasSize(1);
		FestivalRecord record = result.records().get(0);
		assertThat(record.name()).isEqualTo("한강 여름 축제");
		assertThat(record.latitude()).isEqualTo(37.5665);
		assertThat(record.longitude()).isEqualTo(126.978);
		assertThat(record.startDate()).isEqualTo(LocalDate.of(2026, 7, 10));
		assertThat(record.endDate()).isEqualTo(LocalDate.of(2026, 7, 20));
	}

	@Test
	void 날짜가_비어있는_행은_제외되고_집계된다() {
		FestivalJsonlReader.Result result = read(
			row("시작일 없음", "", "2026-07-20"),
			row("종료일 없음", "2026-07-10", ""),
			row("정상", "2026-07-10", "2026-07-20"));

		assertThat(result.records()).extracting(FestivalRecord::name).containsExactly("정상");
		assertThat(result.skippedInvalidDate()).isEqualTo(2);
	}

	@Test
	void 종료된_축제는_제외된다() {
		FestivalJsonlReader.Result result = read(
			row("작년에 끝남", "2020-01-18", "2020-01-27"),
			row("어제 끝남", "2026-07-10", "2026-07-14"),
			row("오늘 끝남", "2026-07-10", "2026-07-15"));

		// endDate < 오늘(KST) 만 제외 — 오늘 끝나는 축제는 아직 진행 중이다 (FR-1).
		assertThat(result.records()).extracting(FestivalRecord::name).containsExactly("오늘 끝남");
		assertThat(result.skippedEnded()).isEqualTo(2);
	}

	@Test
	void 미래_시작_축제는_포함된다() {
		FestivalJsonlReader.Result result = read(row("가을 축제", "2026-10-01", "2026-10-05"));

		assertThat(result.records()).hasSize(1);
		assertThat(result.skippedEnded()).isZero();
	}

	@Test
	void 날짜_파싱_실패_행은_건너뛰고_나머지는_살아남는다() {
		FestivalJsonlReader.Result result = read(
			row("포맷 이상", "2026.07.10", "2026-07-20"),
			row("없는 날짜", "2026-07-10", "2026-13-99"),
			row("정상", "2026-07-10", "2026-07-20"));

		assertThat(result.records()).extracting(FestivalRecord::name).containsExactly("정상");
		assertThat(result.skippedInvalidDate()).isEqualTo(2);
	}

	// 검증: FR-MISSION-16
	@Test
	void 축제_행에서_설명과_장소와_홈페이지를_읽는다() {
		FestivalJsonlReader.Result result = read(
			metadataRow("\"불꽃놀이와 야시장\"", "\"여의도 한강공원 일원\"", "\"https://festival.example.kr\""));

		FestivalRecord record = result.records().get(0);
		assertThat(record.description()).isEqualTo("불꽃놀이와 야시장");
		assertThat(record.place()).isEqualTo("여의도 한강공원 일원");
		assertThat(record.homepage()).isEqualTo("https://festival.example.kr");
	}

	// 검증: FR-MISSION-16
	@Test
	void 홈페이지가_빈_문자열이면_원문_링크가_null이다() {
		// 실측 채움률 66% — 나머지는 빈 문자열이다. 빈 문자열을 저장하면 FE 가 "값 없음"을 두 갈래로 분기한다.
		FestivalJsonlReader.Result blank = read(metadataRow("\"설명\"", "\"장소\"", "\"\""));
		FestivalJsonlReader.Result missing = read("""
			{"name": "링크 없음", "startDate": "2026-07-10", "endDate": "2026-07-20", \
			"latitude": 37.5665, "longitude": 126.978}""");

		assertThat(blank.records().get(0).homepage()).isNull();
		assertThat(missing.records().get(0).homepage()).isNull();
		assertThat(missing.records().get(0).description()).isNull();
		assertThat(missing.records().get(0).place()).isNull();
	}

	// 검증: FR-MISSION-16
	@Test
	void 장소명이_200자를_넘으면_절단된다() {
		// missions.place_name VARCHAR(200) 방어 — title 과 같은 방어선(D2).
		FestivalJsonlReader.Result result = read(
			metadataRow("\"설명\"", "\"" + "가".repeat(250) + "\"", "\"\""));

		assertThat(result.records().get(0).place()).hasSize(200);
	}

	// 검증: FR-MISSION-08 (이미지 미러링은 SRS 등재로 NFR DATA 07)
	@Test
	void 대표_이미지_키를_읽는다() {
		FestivalJsonlReader.Result result = read(imageRow("\"missions/festival/2732106-a1b2c3d4.jpg\""));

		assertThat(result.records().get(0).imageKey()).isEqualTo("missions/festival/2732106-a1b2c3d4.jpg");
	}

	// 검증: FR-MISSION-08
	@Test
	void 대표_이미지_키가_없으면_null이다() {
		// 실측 461건 중 5건이 이미지가 없다 — 결측은 정상 적재고, image_url 만 NULL 로 남는다.
		FestivalJsonlReader.Result missing = read(row("이미지 없음", "2026-07-10", "2026-07-20"));
		FestivalJsonlReader.Result blank = read(imageRow("\"\""));

		assertThat(missing.records().get(0).imageKey()).isNull();
		assertThat(blank.records().get(0).imageKey()).isNull();
	}

	@Test
	void 대표_이미지_키가_미션_경로_밖이면_예외를_던진다() {
		assertThatThrownBy(() -> read(imageRow("\"profiles/original/1/stolen.jpg\"")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("missions/festival/");
	}

	@Test
	void 대표_이미지_키는_접두사_바로_아래_단일_객체여야_한다() {
		// 접두사와 확장자만 보면 통과하지만, 저장된 URL 을 클라이언트가 정규화하면 축제 접두사 밖의 객체를
		// 가리킨다 (Codex 리뷰 파생 — missions/course/other.jpg 로 정규화된다).
		assertThatThrownBy(() -> read(imageRow("\"missions/festival/../course/other.jpg\"")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("단일 객체 이름");
		assertThatThrownBy(() -> read(imageRow("\"missions/festival/2026/a.jpg\"")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("단일 객체 이름");
		// 이름이 비고 확장자만 남은 키도 같은 규칙에서 걸린다.
		assertThatThrownBy(() -> read(imageRow("\"missions/festival/.jpg\"")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("확장자");
	}

	@Test
	void 대표_이미지_키에_확장자가_없으면_예외를_던진다() {
		assertThatThrownBy(() -> read(imageRow("\"missions/festival/2732106-a1b2c3d4\"")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("확장자");
	}

	@Test
	void 대표_이미지_키는_래스터_확장자만_허용한다() {
		// 목적은 형식 통일이 아니라 보안이다 — 공개 읽기인 missions/ 접두사에 실행 가능한 콘텐츠가
		// 올라가면 우리 S3 도메인에서 스크립트가 돈다 (D2).
		assertThat(read(imageRow("\"missions/festival/a.jpg\"")).records()).hasSize(1);
		assertThat(read(imageRow("\"missions/festival/a.BMP\"")).records()).hasSize(1);
		assertThatThrownBy(() -> read(imageRow("\"missions/festival/a.svg\"")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("확장자");
		assertThatThrownBy(() -> read(imageRow("\"missions/festival/a.html\"")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("확장자");
	}

	@Test
	void 이름이_없는_행은_건너뛰고_필수_필드_오류로_집계된다() {
		// 이름이 비면 제목이 빈 미션이 지도와 목록에 뜬다 — 예외가 아니라 정상으로 보이는 오적재다 (D6).
		FestivalJsonlReader.Result result = read(
			requiredFieldRow("\"\"", "37.5665", "126.978"),
			requiredFieldRow("\"   \"", "37.5665", "126.978"),
			"""
				{"startDate": "2026-07-10", "endDate": "2026-07-20", "latitude": 37.5665, "longitude": 126.978}""",
			row("정상", "2026-07-10", "2026-07-20"));

		assertThat(result.records()).extracting(FestivalRecord::name).containsExactly("정상");
		assertThat(result.skippedRequiredField()).isEqualTo(3);
	}

	@Test
	void 좌표가_서비스_범위_밖이면_건너뛰고_필수_필드_오류로_집계된다() {
		// (0,0)은 적도 앞바다, 뒤집힌 위경도는 엉뚱한 격자다 — 둘 다 KoreaCoordinates 범위에서 걸린다.
		FestivalJsonlReader.Result result = read(
			requiredFieldRow("\"좌표 결측\"", "0", "0"),
			requiredFieldRow("\"위경도 뒤집힘\"", "126.978", "37.5665"),
			row("정상", "2026-07-10", "2026-07-20"));

		assertThat(result.records()).extracting(FestivalRecord::name).containsExactly("정상");
		assertThat(result.skippedRequiredField()).isEqualTo(2);
	}

	@Test
	void 좌표가_숫자가_아니면_건너뛰고_필수_필드_오류로_집계된다() {
		FestivalJsonlReader.Result result = read(
			requiredFieldRow("\"문자열 좌표\"", "\"37.5665\"", "\"126.978\""),
			requiredFieldRow("\"빈 좌표\"", "\"\"", "\"\""),
			"""
				{"name": "좌표 키 없음", "startDate": "2026-07-10", "endDate": "2026-07-20"}""");

		assertThat(result.records()).isEmpty();
		assertThat(result.skippedRequiredField()).isEqualTo(3);
	}

	// 검증: FR-MISSION-16
	@Test
	void 소개문의_br_태그가_개행으로_바뀐다() {
		FestivalJsonlReader.Result result = read(
			metadataRow("\"첫 줄<br>둘째 줄<BR />셋째 줄\"", "\"장소\"", "\"\""));

		assertThat(result.records().get(0).description()).isEqualTo("첫 줄\n둘째 줄\n셋째 줄");
	}

	@Test
	void 유효_0건이면_결과가_비어있고_사유별_집계가_남는다() {
		FestivalJsonlReader.Result result = read(
			row("날짜 없음", "", ""),
			row("끝난 축제", "2020-01-18", "2020-01-27"),
			"{깨진 JSON 행");

		assertThat(result.records()).isEmpty();
		assertThat(result.skippedInvalidDate()).isEqualTo(1);
		assertThat(result.skippedEnded()).isEqualTo(1);
		assertThat(result.skippedMalformed()).isEqualTo(1);
	}
}
