package com.msg.fillmap.zone;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import tools.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msg.fillmap.grid.GridEncoder;

/**
 * 격자 표시명("서면 A-14") 명명 규칙의 계약 테스트. 순수 격자 인덱스 산술이라 DB 불요.
 * 규칙: 행 = max_grid_y − gridY 를 A(0)…Z(25)로, 열 = gridX − min_grid_x + 1, 표시명 = "{name} {행}-{열}".
 * 두 zone 에 겹치면 priority 높은 쪽(동률은 zone_key 사전순)이 이기고, 매칭 zone 이 없으면
 * 행정동 이름으로 폴백한다(번호 없이 이름만).
 * 케이스는 언어 중립 픽스처 {@code src/test/resources/fixtures/zone-naming.json} 에서 읽는다 —
 * FE/모바일이 같은 파일을 파싱해 자기 구현을 검증하므로 규칙의 실행형 정본은 픽스처 파일이고,
 * 참조 구현(contains/label/pick/displayName)은 서버에 ZoneNamer 가 없는 동안(표시명 계산은 FE 몫)
 * 이 테스트 안에 유지된다.
 * 근거 결정: docs/MSG-234.md 스펙의 §D2(명명 산술)·§D4(폴백)·§D5(겹침 결정성), docs/MSG-259.md 의 D-4(픽스처).
 */
@DisplayName("격자 표시명 명명 계약 (픽스처 zone-naming.json 이 실행형 정본)")
class ZoneNamingContractTest {

	private record ZoneRect(String zoneKey, String name, int minGridY, int maxGridY,
		int minGridX, int maxGridX, int priority) {
	}

	private record NamingCase(String name, String gridId, List<String> zones, String regionName, String expected) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Fixture(Map<String, ZoneRect> zones, List<NamingCase> cases) {
	}

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

	/** 겹침 시 priority 높은 쪽, 동률은 zone_key 사전순으로 하나만 고른다. 매칭 없으면 null. */
	private static ZoneRect pick(List<ZoneRect> zones, long gridY, long gridX) {
		return zones.stream()
			.filter(z -> contains(z, gridY, gridX))
			.min(Comparator.comparingInt(ZoneRect::priority).reversed()
				.thenComparing(ZoneRect::zoneKey))
			.orElse(null);
	}

	/** 표시명: 매칭 zone 있으면 좌표 산술, 없으면 행정동 이름 폴백(번호 없이 이름만). regionName 도 없으면 null. */
	private static String displayName(String gridId, List<ZoneRect> zones, String regionName) {
		GridEncoder.GridIndex idx = GridEncoder.decode(gridId);
		ZoneRect matched = pick(zones, idx.gridY(), idx.gridX());
		return matched != null ? label(gridId, matched) : regionName;
	}

	private static Fixture loadFixture() {
		try (InputStream in = ZoneNamingContractTest.class.getResourceAsStream("/fixtures/zone-naming.json")) {
			assertThat(in).as("픽스처 파일(/fixtures/zone-naming.json) 존재").isNotNull();
			return new ObjectMapper().readValue(in, Fixture.class);
		} catch (IOException e) {
			throw new IllegalStateException("픽스처 로드 실패", e);
		}
	}

	@Test
	@DisplayName("픽스처 케이스가 비어 있으면 실패한다 (파일 누락·파싱 실패가 조용히 0건 통과로 새지 않게)")
	void 픽스처_케이스가_비어있으면_테스트가_실패한다() {
		Fixture fixture = loadFixture();
		assertThat(fixture.zones()).isNotEmpty();
		assertThat(fixture.cases()).isNotEmpty();
	}

	@TestFactory
	@DisplayName("픽스처 전 케이스: displayName(gridId, 활성 zones, regionName) == expected")
	Stream<DynamicTest> 픽스처_케이스를_전부_검증한다() {
		Fixture fixture = loadFixture();
		return fixture.cases().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
			List<ZoneRect> active = c.zones().stream()
				.map(key -> {
					ZoneRect zone = fixture.zones().get(key);
					assertThat(zone).as("케이스 [%s] 가 참조한 zone 키 [%s] 미정의", c.name(), key).isNotNull();
					return zone;
				})
				.toList();
			assertThat(displayName(c.gridId(), active, c.regionName())).isEqualTo(c.expected());
		}));
	}
}
