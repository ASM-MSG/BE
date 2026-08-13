package com.msg.fillmap.mission.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.mission.entity.MissionGrid;

/**
 * 시더 순수 로직 검증 (MSG-224 모듈 2, DB 무관). 9×9 전개(D2) · KST→UTC 변환(D5, MSG-223 §D3 계약) ·
 * 중심 격자+기간 dedupe 키(D3)를 본다. 러너 배선·DB 경로는 통합 테스트(모듈 3) 담당.
 */
// 검증: FR-MISSION-08
@DisplayName("FestivalMissionSeeder 순수 로직 — 9×9 전개·시간 변환·dedupe")
class FestivalMissionSeederTest {

	private static final double 시청_LAT = 37.5665;
	private static final double 시청_LON = 126.978;

	/** dedupe 키는 좌표와 기간만 보므로 화면용 메타데이터(MSG-383 D3)는 픽스처에서 비워 둔다. */
	private static FestivalRecord record(String name, double lat, double lon, LocalDate start, LocalDate end) {
		return new FestivalRecord(name, lat, lon, start, end, null, null, null);
	}

	@Test
	void 중심_좌표에서_9x9_격자_81개가_전개된다() {
		String center = GridEncoder.encode(시청_LAT, 시청_LON);
		GridIndex index = GridEncoder.decode(center);

		List<String> grids = FestivalMissionSeeder.expandGrids(center);

		// 중심±4 경계 실측 — 네 꼭짓점과 중심이 모두 포함되고 중복이 없다 (FR-2).
		assertThat(grids).hasSize(81).doesNotHaveDuplicates()
			.contains(center)
			.contains((index.gridY() - 4) + "_" + (index.gridX() - 4))
			.contains((index.gridY() - 4) + "_" + (index.gridX() + 4))
			.contains((index.gridY() + 4) + "_" + (index.gridX() - 4))
			.contains((index.gridY() + 4) + "_" + (index.gridX() + 4));
	}

	@Test
	void KST_날짜가_UTC_순간으로_변환된다() {
		// "7/1~7/5" 축제 — MSG-223 §D3 계약 값 그대로 (D5).
		assertThat(FestivalMissionSeeder.toUtcStart(LocalDate.of(2026, 7, 1)))
			.isEqualTo(LocalDateTime.of(2026, 6, 30, 15, 0, 0));
		assertThat(FestivalMissionSeeder.toUtcEnd(LocalDate.of(2026, 7, 5)))
			.isEqualTo(LocalDateTime.of(2026, 7, 5, 14, 59, 59));
	}

	@Test
	void 같은_중심격자_같은_기간의_소스_행은_한_건만_남는다() {
		// 기관별 파일의 이름 변형 중복 — 좌표가 미세하게 달라도 같은 격자로 양자화되면 흡수한다 (D3).
		FestivalRecord first = record("제8회 한강 축제", 시청_LAT, 시청_LON,
			LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5));
		FestivalRecord renamed = record("2026 한강 축제", 시청_LAT + 0.00001, 시청_LON + 0.00001,
			LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5));

		assertThat(FestivalMissionSeeder.dedupeSource(List.of(first, renamed))).containsExactly(first);
	}

	@Test
	void 기간이_다르면_같은_좌표라도_별건이다() {
		// 내년 회차 — 같은 곳이어도 기간이 다르면 새 미션이다 (D3).
		FestivalRecord thisYear = record("한강 축제", 시청_LAT, 시청_LON,
			LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5));
		FestivalRecord nextYear = record("한강 축제", 시청_LAT, 시청_LON,
			LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 5));

		assertThat(FestivalMissionSeeder.dedupeSource(List.of(thisYear, nextYear)))
			.containsExactly(thisYear, nextYear);
	}

	@Test
	void 기존_미션의_중심_격자가_min_플러스4로_복원된다() {
		String center = GridEncoder.encode(35.1796, 129.0756);
		List<MissionGrid> grids = FestivalMissionSeeder.expandGrids(center).stream()
			.map(gridId -> new MissionGrid(7L, gridId))
			.toList();

		// 9×9 블록(중심±4)이라 min(gridY)+4, min(gridX)+4 가 결정적으로 중심이다 (D3).
		assertThat(FestivalMissionSeeder.restoreCenter(grids)).isEqualTo(center);
	}
}
