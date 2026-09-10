package com.msg.fillmap.event.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;

/**
 * 행사 회차 저장소의 콘솔 후보 파생 쿼리 (실 PostgreSQL, MSG-501). 검증 대상은 종료 시각 경계 하나다 —
 * 반개구간이라 종료 정각 회차가 빠지고, 노출 시작 전 예정 회차는 남는다(결정 D-2).
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg501-*)만 쓰고 {@code @Transactional} 롤백한다. 조회가 테이블 전량을
 * 후보로 읽으므로 단언은 항상 이 테스트가 만든 id 로 좁힌 뒤 한다 — 시드·주변 데이터가 있어도 결과가
 * 흔들리지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("EventOccurrenceRepository 콘솔 후보 조회 (실 PostgreSQL)")
class EventOccurrenceRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	private String 키(String suffix) {
		return "msg501-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private EventOccurrence 회차(EventSeries series, LocalDateTime startsAt, LocalDateTime endsAt) {
		EventOccurrence occurrence = new EventOccurrence(series, 키("occ"));
		occurrence.update(series, "테스트 행사", "부산", startsAt, endsAt, 100, 102, 200, 202);
		return occurrenceRepository.save(occurrence);
	}

	private List<Long> 내_후보(List<EventOccurrence> mine) {
		List<Long> ids = mine.stream().map(EventOccurrence::getId).toList();
		return occurrenceRepository.findByEndsAtAfter(NOW).stream()
			.map(EventOccurrence::getId)
			.filter(ids::contains)
			.toList();
	}

	@Nested
	@DisplayName("findByEndsAtAfter")
	class FindByEndsAtAfter {

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("진행 중이거나 예정인 회차만 후보에 담긴다")
		void 종료_전_회차만_후보에_담긴다() {
			EventSeries series = seriesRepository.save(new EventSeries(키("series"), "테스트 시리즈"));
			EventOccurrence 예정 = 회차(series, NOW.plusDays(3), NOW.plusDays(4));
			EventOccurrence 진행중 = 회차(series, NOW.minusDays(1), NOW.plusDays(1));
			EventOccurrence 유예 = 회차(series, NOW.minusDays(5), NOW.minusDays(1));

			assertThat(내_후보(List.of(예정, 진행중, 유예)))
				.containsExactlyInAnyOrder(예정.getId(), 진행중.getId());
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("종료 정각 회차는 빠진다 — 경계는 반개구간이다")
		void 종료_정각_회차는_후보에서_빠진다() {
			EventSeries series = seriesRepository.save(new EventSeries(키("series"), "테스트 시리즈"));
			EventOccurrence 정각종료 = 회차(series, NOW.minusDays(1), NOW);
			EventOccurrence 일초남음 = 회차(series, NOW.minusDays(1), NOW.plusSeconds(1));

			assertThat(내_후보(List.of(정각종료, 일초남음))).containsExactly(일초남음.getId());
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("노출 시작 전 예정 회차도 후보에 담긴다 — 콘솔에는 visible_from 을 걸지 않는다")
		void 노출_시작_전_예정_회차도_후보에_담긴다() {
			EventSeries series = seriesRepository.save(new EventSeries(키("series"), "테스트 시리즈"));
			EventOccurrence 노출전 = 회차(series, NOW.plusDays(60), NOW.plusDays(61));

			assertThat(노출전.getVisibleFrom()).isAfter(NOW);
			assertThat(내_후보(List.of(노출전))).containsExactly(노출전.getId());
		}
	}
}
