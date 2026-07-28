package com.msg.fillmap.zone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.msg.fillmap.grid.GridEncoder;

/**
 * 명명 산술 계약 (MSG-234 §D2 정본 · 순수 함수 픽스처).
 * 서버 ZoneNamer 는 MVP 미구현이라(§D2, 라벨=FE-local 산술) 이 테스트가 명명 규칙의 실행형 정본이다 —
 * FE/모바일 포팅과 후속 ZoneNamer 이식이 이 표에 맞아야 한다. 순수 격자 인덱스 산술이라 DB 불요.
 * 규칙: 행 = max_grid_y − gridY 를 A(0)…Z(25), 열 = gridX − min_grid_x + 1, 표시명 = "{name} {행}-{열}".
 * 겹침은 priority DESC, zone_key ASC 로 하나(§D5), 매칭 없으면 행정동 이름 폴백(번호 없음 §D4).
 */
@DisplayName("명명 산술 계약 (§D2 정본)")
class ZoneNamingContractTest {

	private record ZoneRect(String zoneKey, String name, int minGridY, int maxGridY,
		int minGridX, int maxGridX, int priority) {
	}

	private static final ZoneRect SEOMYEON =
		new ZoneRect("m234-seomyeon", "m234서면", 1000, 1010, 2000, 2020, 0);

	private static boolean contains(ZoneRect z, long gridY, long gridX) {
		return gridY >= z.minGridY() && gridY <= z.maxGridY()
			&& gridX >= z.minGridX() && gridX <= z.maxGridX();
	}

	private static String label(String gridId, ZoneRect z) {
		GridEncoder.GridIndex idx = GridEncoder.decode(gridId);
		char letter = (char) ('A' + (z.maxGridY() - idx.gridY()));
		long col = idx.gridX() - z.minGridX() + 1;
		return z.name() + " " + letter + "-" + col;
	}

	/** 겹침 결정성(§D5): priority DESC, zone_key ASC 로 하나. 매칭 없으면 null. */
	private static ZoneRect pick(List<ZoneRect> zones, long gridY, long gridX) {
		return zones.stream()
			.filter(z -> contains(z, gridY, gridX))
			.min(Comparator.comparingInt(ZoneRect::priority).reversed()
				.thenComparing(ZoneRect::zoneKey))
			.orElse(null);
	}

	/** 표시명: 매칭 zone 있으면 좌표 산술, 없으면 행정동 이름 폴백(번호 없음 §D4). */
	private static String displayName(String gridId, List<ZoneRect> zones, String regionName) {
		GridEncoder.GridIndex idx = GridEncoder.decode(gridId);
		ZoneRect matched = pick(zones, idx.gridY(), idx.gridX());
		return matched != null ? label(gridId, matched) : regionName;
	}

	@Test
	@DisplayName("북단·서단 격자는 A-1 로 명명된다")
	void 북단_서단_격자는_A_1로_명명된다() {
		// 북단 = max_grid_y(1010), 서단 = min_grid_x(2000) → rowIndex 0='A', col 1
		assertThat(label("1010_2000", SEOMYEON)).isEqualTo("m234서면 A-1");
	}

	@Test
	@DisplayName("북단에서 14번째 열은 A-14 다")
	void 북단에서_14번째_열은_A_14다() {
		// col = grid_x − min_grid_x + 1 = 2013 − 2000 + 1
		assertThat(label("1010_2013", SEOMYEON)).isEqualTo("m234서면 A-14");
	}

	@Test
	@DisplayName("한 행 남쪽은 B 로 내려간다")
	void 한_행_남쪽은_B로_내려간다() {
		// rowIndex 1 = 'B' (max_grid_y − grid_y)
		assertThat(label("1009_2000", SEOMYEON)).isEqualTo("m234서면 B-1");
	}

	@Test
	@DisplayName("남단 행은 사각형 높이에 따라 알파벳이 증가한다 (max−min=25 → Z 경계)")
	void 남단_행은_사각형_높이에_따라_알파벳이_증가한다() {
		ZoneRect tall = new ZoneRect("m234-tall", "m234높은구역", 1000, 1025, 2000, 2000, 0); // 높이 25(V8 CHECK 상한)
		// 남단 = min_grid_y(1000) → rowIndex 25 → 'A'+25 = 'Z'
		assertThat(label("1000_2000", tall)).isEqualTo("m234높은구역 Z-1");
	}

	@Test
	@DisplayName("사각형 밖 격자는 zone 매칭이 아니다")
	void 사각형_밖_격자는_zone_매칭이_아니다() {
		assertThat(contains(SEOMYEON, 1011, 2000)).isFalse(); // 북쪽 밖
		assertThat(contains(SEOMYEON, 1005, 2021)).isFalse(); // 동쪽 밖
		assertThat(contains(SEOMYEON, 1005, 2010)).isTrue();  // 안
	}

	@Test
	@DisplayName("두 zone 에 겹치는 격자는 priority 가 높은 zone 을 고른다 (priority DESC, zone_key ASC)")
	void 두_zone에_겹치는_격자는_priority가_높은_zone을_고른다() {
		ZoneRect big = new ZoneRect("m234-big", "m234큰구역", 1000, 1010, 2000, 2020, 0);
		ZoneRect hot = new ZoneRect("m234-hot", "m234핫스팟", 1004, 1006, 2008, 2012, 10);
		// (1005, 2010) 은 둘 다 포함 → priority 10 인 hot 이 이긴다
		assertThat(pick(List.of(big, hot), 1005, 2010)).isEqualTo(hot);
	}

	@Test
	@DisplayName("매칭 zone 이 없으면 행정동 이름으로 폴백한다 (번호 없음 §D4)")
	void 매칭_zone이_없으면_행정동_이름으로_폴백한다() {
		String region = "부산광역시 부산진구 부전동";
		// 어느 zone 밖 → regionName 폴백(번호 없음)
		assertThat(displayName("9999_9999", List.of(SEOMYEON), region)).isEqualTo(region);
		// zone 안이면 좌표 명명이 폴백보다 우선
		assertThat(displayName("1010_2000", List.of(SEOMYEON), region)).isEqualTo("m234서면 A-1");
	}
}
