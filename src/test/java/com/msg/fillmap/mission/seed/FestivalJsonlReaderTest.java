package com.msg.fillmap.mission.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * festivals.jsonl 파서 검증 (MSG-224 모듈 1, 순수 단위 · DB 무관). 행 필터 3종(날짜 누락/파싱 실패 ·
 * 종료 축제 · JSON 파싱 실패)과 사유별 집계, "오늘(KST)" 주입 경계를 본다 (D1).
 */
@DisplayName("FestivalJsonlReader 행 파싱·필터·사유별 집계")
class FestivalJsonlReaderTest {

	private static final LocalDate TODAY_KST = LocalDate.of(2026, 7, 15);

	private final FestivalJsonlReader reader = new FestivalJsonlReader(new ObjectMapper());

	private FestivalJsonlReader.Result read(String... lines) {
		InputStream in = new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		return reader.read(in, TODAY_KST);
	}

	/** 실측 스키마(D1) 형태의 1행 — 미적재 필드(place 등)도 원본처럼 포함한다. */
	private static String row(String name, String startDate, String endDate) {
		return """
			{"name": "%s", "place": "행사장 일원", "startDate": "%s", "endDate": "%s", \
			"description": "개막행사 등", "latitude": 37.5665, "longitude": 126.978, \
			"referenceDate": "2026-07-23", "homepage": "", "sourceOrg": "서울특별시_문화축제"}"""
			.formatted(name, startDate, endDate);
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
