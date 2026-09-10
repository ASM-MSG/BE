package com.msg.fillmap.event.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 행사 회차의 파생 상태 (MSG-438). status·uploadClosesAt 은 저장 컬럼이 아니라 starts_at·ends_at 와
 * 주입된 시각만으로 계산되고, 이 정의를 MSG-439 조회가 그대로 쓴다 — 그래서 여기가 정의의 정본 테스트다.
 */
@DisplayName("EventOccurrence 파생 상태 (저장 컬럼 없음)")
class EventOccurrenceStatusTest {

	private static final LocalDateTime 시작 = LocalDateTime.of(2026, 10, 6, 1, 0);
	private static final LocalDateTime 종료 = LocalDateTime.of(2026, 10, 15, 13, 0);

	private EventOccurrence 회차() {
		EventOccurrence occurrence = new EventOccurrence(null, "msg438-status");
		occurrence.update(null, "제목", "부산", 시작, 종료, 1, 2, 3, 4);
		return occurrence;
	}

	// 검증: FR-EVENT-07
	@Test
	@DisplayName("상태 파생 계산은 반개구간이다 — 경계 정각은 다음 상태에 속한다")
	void 상태_파생_계산은_반개구간이다() {
		EventOccurrence occurrence = 회차();
		LocalDateTime 마감 = occurrence.uploadClosesAt();

		assertThat(occurrence.statusAt(시작.minusNanos(1))).isEqualTo(EventStatus.UPCOMING);
		assertThat(occurrence.statusAt(시작)).isEqualTo(EventStatus.LIVE);
		assertThat(occurrence.statusAt(종료.minusNanos(1))).isEqualTo(EventStatus.LIVE);
		assertThat(occurrence.statusAt(종료)).isEqualTo(EventStatus.UPLOAD_GRACE);
		assertThat(occurrence.statusAt(마감.minusNanos(1))).isEqualTo(EventStatus.UPLOAD_GRACE);
		assertThat(occurrence.statusAt(마감)).isEqualTo(EventStatus.ARCHIVED);
	}

	@Test
	@DisplayName("업로드 마감 시각은 종료 30일 후로 파생된다")
	void 업로드_마감_시각은_종료_30일_후로_파생된다() {
		assertThat(회차().uploadClosesAt()).isEqualTo(종료.plusDays(30));
	}

	@Test
	@DisplayName("칩 노출 시작은 시작 2주 전으로 함께 세팅된다 (DDL CHECK 등식의 코드 쪽 짝)")
	void 칩_노출_시작은_시작_2주_전으로_함께_세팅된다() {
		assertThat(회차().getVisibleFrom()).isEqualTo(시작.minusDays(14));
	}
}
