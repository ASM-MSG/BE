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
 * popups.jsonl 파서·검증 (MSG-235 모듈 1, 순수 단위 · DB 무관). 산출물 결함은 축제 파서의 행 스킵·집계와
 * 달리 **즉시 예외**(전량 거부, D6)이고, 종료 필터(closeDate &lt; todayKst)만 skip+집계다. periodType
 * 미사용(날짜 직접 판정)도 여기서 증명한다.
 */
// 검증: FR-MISSION-10
@DisplayName("PopupJsonlReader 행 파싱·전량 검증 — 위반 즉시 예외, 종료만 집계")
class PopupJsonlReaderTest {

	private static final LocalDate TODAY_KST = LocalDate.of(2026, 7, 15);

	private final PopupJsonlReader reader = new PopupJsonlReader(new ObjectMapper());

	private PopupJsonlReader.Result read(String... lines) {
		InputStream in = new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		return reader.read(in, TODAY_KST);
	}

	/** 실측 스키마(D1) 형태의 1행 — 미적재 필드(periodType)도 원본처럼 포함한다. */
	private static String row(long id, String name, String periodType, String openDate, String closeDate,
		String latitude, String longitude) {
		return """
			{"id": %d, "periodType": "%s", "openDate": "%s", "closeDate": "%s", \
			"operationTime": ["매일 11:00 ~ 20:00"], "latitude": %s, "longitude": %s, \
			"address": "서울 성동구 동일로 119 (성수동2가)", "addressDetail": "성수 스테이지", \
			"roadAddress": "서울 성동구 동일로 119", "name": "%s", "sourceUrl": "https://popga.co.kr/popup/%d"}"""
			.formatted(id, periodType, openDate, closeDate, latitude, longitude, name, id);
	}

	private static String row(long id, String name, String openDate, String closeDate) {
		return row(id, name, "IN_PROGRESS", openDate, closeDate, "37.5434116044", "127.0643072071");
	}

	/** 결함 주입용 — id·name·좌표·날짜를 raw JSON 조각으로 받아 위반 형태를 그대로 만든다. */
	private static String rawRow(String idJson, String nameJson, String latJson, String lonJson,
		String openJson, String closeJson) {
		return """
			{"id": %s, "periodType": "IN_PROGRESS", "openDate": %s, "closeDate": %s, \
			"operationTime": [], "latitude": %s, "longitude": %s, "name": %s, \
			"sourceUrl": "https://popga.co.kr/popup/0"}"""
			.formatted(idJson, openJson, closeJson, latJson, lonJson, nameJson);
	}

	/** 화면용 필드(D3)를 원문 조각 그대로 받는 1행 — 주소 결측·운영시간 배열 형태를 그대로 만든다. */
	private static String metadataRow(String operationTimeJson, String addressJson, String roadAddressJson,
		String addressDetailJson) {
		return """
			{"id": 9001, "periodType": "IN_PROGRESS", "openDate": "2026-07-10", "closeDate": "2026-07-20", \
			"latitude": 37.5434, "longitude": 127.0643, "name": "메타데이터 팝업", \
			"operationTime": %s, "address": %s, "roadAddress": %s, "addressDetail": %s, \
			"sourceUrl": "https://popga.co.kr/popup/9001"}"""
			.formatted(operationTimeJson, addressJson, roadAddressJson, addressDetailJson);
	}

	/** 보강 수집(MSG-394 D1)이 더한 두 키만 원문 조각 그대로 받는 1행. */
	private static String enrichedRow(String descriptionJson, String imageKeyJson) {
		return """
			{"id": 9002, "periodType": "IN_PROGRESS", "openDate": "2026-07-10", "closeDate": "2026-07-20", \
			"latitude": 37.5434, "longitude": 127.0643, "name": "보강된 팝업", "operationTime": [], \
			"roadAddress": "서울 성동구 동일로 119", "sourceUrl": "https://popga.co.kr/popup/9002", \
			"description": %s, "imageKey": %s}"""
			.formatted(descriptionJson, imageKeyJson);
	}

	@Test
	void 유효_행이_레코드로_매핑된다() {
		PopupJsonlReader.Result result = read(
			row(8151, "플랏투 팝업 - 대구 팝업", "IN_PROGRESS", "2026-07-10", "2026-07-20",
				"35.8735041864", "128.5908069973"));

		assertThat(result.records()).hasSize(1);
		PopupRecord record = result.records().get(0);
		assertThat(record.id()).isEqualTo(8151L);
		assertThat(record.name()).isEqualTo("플랏투 팝업 - 대구 팝업");
		assertThat(record.latitude()).isEqualTo(35.8735041864);
		assertThat(record.longitude()).isEqualTo(128.5908069973);
		assertThat(record.openDate()).isEqualTo(LocalDate.of(2026, 7, 10));
		assertThat(record.closeDate()).isEqualTo(LocalDate.of(2026, 7, 20));
		assertThat(result.skippedEnded()).isZero();
	}

	// 검증: FR-MISSION-16
	@Test
	void 팝업_운영시간_배열을_순서대로_개행으로_이어_붙인다() {
		String multiline = """
			["매일 11:00 ~ 20:00", "라스트 오더 19:30", "매주 월요일 휴무"]""";

		PopupRecord record = read(metadataRow(multiline, "\"지번\"", "\"도로명\"", "\"\"")).records().get(0);

		assertThat(record.operationTime()).isEqualTo("매일 11:00 ~ 20:00\n라스트 오더 19:30\n매주 월요일 휴무");
		assertThat(record.sourceUrl()).isEqualTo("https://popga.co.kr/popup/9001");
	}

	// 검증: FR-MISSION-16
	@Test
	void 운영시간이_비면_null이다() {
		assertThat(read(metadataRow("[]", "\"지번\"", "\"도로명\"", "\"\"")).records().get(0).operationTime()).isNull();
		assertThat(read(metadataRow("null", "\"지번\"", "\"도로명\"", "\"\"")).records().get(0).operationTime()).isNull();
	}

	// 검증: FR-MISSION-16
	@Test
	void 팝업_상세주소가_있으면_도로명주소_뒤에_공백으로_붙인다() {
		PopupRecord record = read(metadataRow("[]", "\"서울 성동구 성수동2가 315-70\"",
			"\"서울 성동구 동일로 119\"", "\"성수 스테이지\"")).records().get(0);

		assertThat(record.placeName()).isEqualTo("서울 성동구 동일로 119 성수 스테이지");
	}

	// 검증: FR-MISSION-16
	@Test
	void 팝업_도로명주소가_비면_지번주소를_위치로_쓴다() {
		// 실측 결측 0건이라 실행되지 않는 분기지만, 위치 한 줄이 비면 카드가 무너진다 (D3).
		PopupRecord fallback = read(metadataRow("[]", "\"서울 성동구 성수동2가 315-70\"", "\"\"", "\"\""))
			.records().get(0);
		PopupRecord none = read(metadataRow("[]", "\"\"", "\"\"", "\"\"")).records().get(0);

		assertThat(fallback.placeName()).isEqualTo("서울 성동구 성수동2가 315-70");
		assertThat(none.placeName()).isNull();
	}

	@Test
	void 종료된_팝업은_제외되고_집계된다() {
		// periodType 이 IN_PROGRESS 라고 우겨도 날짜로 판정한다 — 주 1회 스냅샷은 최대 7일 낡지만 날짜는 낡지
		// 않는다(D6). 반대로 periodType END 여도 closeDate 가 오늘이면 아직 진행 중이다 (FR-1).
		PopupJsonlReader.Result result = read(
			row(1, "어제 끝남", "IN_PROGRESS", "2026-07-01", "2026-07-14", "37.5434", "127.0643"),
			row(2, "오늘 끝남", "END", "2026-07-01", "2026-07-15", "37.5434", "127.0643"),
			row(3, "진행 중", "2026-07-10", "2026-07-20"));

		assertThat(result.records()).extracting(PopupRecord::name).containsExactly("오늘 끝남", "진행 중");
		assertThat(result.skippedEnded()).isEqualTo(1);
	}

	@Test
	void 미래_시작_팝업은_포함된다() {
		// READY(미래 시작)도 적재 대상 — 노출은 findActive 가 숨긴다 (FR-1, 축제 D1 동일).
		PopupJsonlReader.Result result = read(
			row(3, "가을 팝업", "READY", "2026-10-01", "2026-10-05", "37.5434", "127.0643"));

		assertThat(result.records()).hasSize(1);
		assertThat(result.skippedEnded()).isZero();
	}

	@Test
	void 좌표가_한국_범위_밖이면_전량_거부된다() {
		for (String[] outside : new String[][] {
			{"32.999", "127.0"}, {"39.001", "127.0"}, {"36.5", "123.999"}, {"36.5", "132.001"}}) {
			assertThatThrownBy(() -> read(
				row(1, "범위 밖", "IN_PROGRESS", "2026-07-10", "2026-07-20", outside[0], outside[1])))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("한국 범위");
		}
		// 경계는 포함이다 — 33/39·124/132 정확값은 통과한다 (FR-6).
		assertThat(read(
			row(1, "남서 경계", "IN_PROGRESS", "2026-07-10", "2026-07-20", "33", "124"),
			row(2, "북동 경계", "IN_PROGRESS", "2026-07-10", "2026-07-20", "39", "132"))
			.records()).hasSize(2);
	}

	@Test
	void 좌표가_누락되거나_숫자가_아니면_전량_거부된다() {
		String missingPair = """
			{"id": 1, "openDate": "2026-07-10", "closeDate": "2026-07-20", "name": "좌표 없음"}""";
		String stringCoord = rawRow("2", "\"문자열 좌표\"", "\"37.5434\"", "127.0643",
			"\"2026-07-10\"", "\"2026-07-20\"");
		String nullCoord = rawRow("3", "\"null 좌표\"", "36.5", "null", "\"2026-07-10\"", "\"2026-07-20\"");

		for (String bad : new String[] {missingPair, stringCoord, nullCoord}) {
			assertThatThrownBy(() -> read(bad))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("유한 숫자");
		}
	}

	@Test
	void openDate가_closeDate보다_늦으면_전량_거부된다() {
		assertThatThrownBy(() -> read(row(1, "날짜 역전", "2026-07-20", "2026-07-10")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("closeDate");
	}

	@Test
	void 날짜가_누락되거나_파싱_불가면_전량_거부된다() {
		String missingOpen = """
			{"id": 1, "name": "시작일 없음", "latitude": 36.5, "longitude": 127.5, "closeDate": "2026-07-20"}""";

		assertThatThrownBy(() -> read(missingOpen))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("openDate");
		assertThatThrownBy(() -> read(row(2, "포맷 이상", "2026.07.10", "2026-07-20")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ISO");
		assertThatThrownBy(() -> read(row(3, "없는 날짜", "2026-07-10", "2026-13-99")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ISO");
	}

	@Test
	void id가_정수가_아니면_전량_거부된다() {
		// 문자열 "8151" 도 관용 변환 없이 거부한다 (코스 requireText 원칙, D6).
		for (String badId : new String[] {"\"8151\"", "81.5", "null"}) {
			assertThatThrownBy(() -> read(
				rawRow(badId, "\"이름\"", "36.5", "127.5", "\"2026-07-10\"", "\"2026-07-20\"")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("id");
		}
	}

	@Test
	void id가_중복이면_전량_거부된다() {
		// 크롤러가 id 유일을 보장하므로 중복 = 산출물 결함 — 멱등 키 보전 (D3).
		assertThatThrownBy(() -> read(
			row(7, "먼저", "2026-07-10", "2026-07-20"),
			row(7, "나중", "2026-08-01", "2026-08-05")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("중복");
	}

	@Test
	void name이_문자열이_아니거나_비어있으면_전량_거부된다() {
		for (String badName : new String[] {"123", "\"   \"", "null"}) {
			assertThatThrownBy(() -> read(
				rawRow("1", badName, "36.5", "127.5", "\"2026-07-10\"", "\"2026-07-20\"")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("name");
		}
	}

	// 검증: FR-MISSION-16
	@Test
	void 팝업_소개문을_읽는다() {
		// 팝가 상세 페이지 본문 원문 그대로다 — 잘린 og:description 은 어느 경우에도 쓰지 않는다 (D1).
		PopupRecord record = read(enrichedRow("\"한여름 밤의 세일\"", "\"missions/popup/8151-a1b2c3d4.jpg\""))
			.records().get(0);

		assertThat(record.description()).isEqualTo("한여름 밤의 세일");
	}

	// 검증: FR-MISSION-16
	@Test
	void 팝업_소개문이_없으면_null이다() {
		// 소개문 결측은 정상 결과다 — 못 얻었을 때 안전한 실패는 잘린 값이 아니라 값 없음이다 (D1).
		PopupRecord missing = read(row(8151, "소개문 없음", "2026-07-10", "2026-07-20")).records().get(0);
		PopupRecord blank = read(enrichedRow("\"   \"", "null")).records().get(0);

		assertThat(missing.description()).isNull();
		assertThat(blank.description()).isNull();
	}

	// 검증: FR-MISSION-16
	@Test
	void 팝업_소개문의_br_태그가_개행으로_바뀐다() {
		String description = "\"오픈 기념 이벤트<br>선착순 100명<br/>매장 방문 필수<BR />끝\"";

		PopupRecord record = read(enrichedRow(description, "null")).records().get(0);

		assertThat(record.description())
			.isEqualTo("오픈 기념 이벤트\n선착순 100명\n매장 방문 필수\n끝")
			.doesNotContainIgnoringCase("<br");
	}

	// 검증: FR-MISSION-08 (이미지 미러링은 SRS 등재로 NFR DATA 07)
	@Test
	void 팝업_대표_이미지_키를_읽는다() {
		PopupRecord record = read(enrichedRow("null", "\"missions/popup/8151-a1b2c3d4.jpg\"")).records().get(0);

		assertThat(record.imageKey()).isEqualTo("missions/popup/8151-a1b2c3d4.jpg");
	}

	// 검증: FR-MISSION-08
	@Test
	void 팝업_대표_이미지_키가_없으면_null이다() {
		// 포스터를 못 얻은 팝업은 이미지 없는 미션으로 정상 적재된다 (D1 필드 단위 판정).
		PopupRecord missing = read(row(8151, "이미지 없음", "2026-07-10", "2026-07-20")).records().get(0);
		PopupRecord blank = read(enrichedRow("null", "\"\"")).records().get(0);

		assertThat(missing.imageKey()).isNull();
		assertThat(blank.imageKey()).isNull();
	}

	@Test
	void 팝업_대표_이미지_키가_팝업_경로_밖이면_예외를_던진다() {
		// 축제 경로 키도 거부한다 — 종류별 접두사가 검증의 일부다 (D3).
		for (String outside : new String[] {
			"\"profiles/original/1/stolen.jpg\"", "\"missions/festival/2732106-a1b2c3d4.jpg\""}) {
			assertThatThrownBy(() -> read(enrichedRow("null", outside)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("missions/popup/");
		}
	}

	@Test
	void 팝업_대표_이미지_키에_하위_경로가_있으면_예외를_던진다() {
		// 접두사와 확장자만 보면 통과하지만, 저장된 URL 을 클라이언트가 정규화하면 팝업 접두사 밖의 객체를
		// 가리킨다 (D3 경로 탈출 방어).
		assertThatThrownBy(() -> read(enrichedRow("null", "\"missions/popup/../festival/other.jpg\"")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("단일 객체 이름");
		assertThatThrownBy(() -> read(enrichedRow("null", "\"missions/popup/2026/a.jpg\"")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("단일 객체 이름");
	}

	@Test
	void 팝업_대표_이미지_키에_URL_구분자가_있으면_예외를_던진다() {
		// 확장자 허용목록만으로는 그 확장자가 실제로 요청되는 자원인지를 보장하지 못한다 — 저장 값이
		// 이스케이프 없이 이어 붙인 URL 이라 "payload.html#x.jpg" 는 클라이언트가 payload.html 을
		// 요청한다(? 는 쿼리로 같은 결과). 셋 다 지금 확장자 검사를 통과하는 형태다 (Codex 리뷰 파생).
		for (String bypass : new String[] {"\"missions/popup/payload.html#x.jpg\"",
			"\"missions/popup/payload.html?x.jpg\"", "\"missions/popup/payload.html%23x.jpg\"",
			"\"missions/popup/a b.jpg\""}) {
			assertThatThrownBy(() -> read(enrichedRow("null", bypass)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("허용되지 않는 문자");
		}
	}

	@Test
	void 팝업_대표_이미지_키는_래스터_확장자만_허용한다() {
		// 목적은 형식 통일이 아니라 보안이다 — 공개 읽기인 missions/ 접두사에 실행 가능한 콘텐츠가
		// 올라가면 우리 S3 도메인에서 스크립트가 돈다 (D3).
		assertThat(read(enrichedRow("null", "\"missions/popup/a.webp\"")).records()).hasSize(1);
		for (String executable : new String[] {"\"missions/popup/a.svg\"", "\"missions/popup/a.html\"",
			"\"missions/popup/a\""}) {
			assertThatThrownBy(() -> read(enrichedRow("null", executable)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("확장자");
		}
	}

	@Test
	void JSON_파싱_실패_행이_있으면_전량_거부된다() {
		// 축제의 skip+집계와 다른 계약 — 크롤 완성 산출물의 깨진 행은 재크롤 대상이다 (D6).
		assertThatThrownBy(() -> read(row(1, "정상", "2026-07-10", "2026-07-20"), "{깨진 JSON 행"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("파싱");
	}
}
