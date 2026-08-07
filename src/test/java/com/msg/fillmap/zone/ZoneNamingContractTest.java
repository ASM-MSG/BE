package com.msg.fillmap.zone;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
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
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 격자 표시명("서면 A-14") 명명 규칙의 계약 테스트. 순수 격자 인덱스 산술이라 DB 불요.
 * 규칙: 행 = max_grid_y − gridY 를 A(0)…Z(25)로, 열 = gridX − min_grid_x + 1, 표시명 = "{name} {행}-{열}".
 * 두 zone 에 겹치면 priority 높은 쪽(동률은 zone_key 사전순)이 이기고, 매칭 zone 이 없으면
 * 행정동 이름으로 폴백한다(번호 없이 이름만) — 폴백 조립은 클라이언트 몫이라 서버는 두 필드를 null 로 낸다.
 * 케이스는 언어 중립 픽스처 {@code src/test/resources/fixtures/zone-naming.json} 에서 읽는다 —
 * 규칙의 실행형 정본은 그 파일 하나다. MSG-341 부터 명명 계산 구현은 서버 한 곳(응답 필드 zoneName·zoneCell)이라
 * 픽스처를 검증에 쓰는 것은 이 테스트뿐이고, 클라이언트는 명명 구현 없이 조립과 폴백 표시만 한다.
 * 판정 대상은 실물 {@link ZoneNameResolver} 다 (D-3 — 계산이 서버로 오면서 테스트 안에 두었던 참조 구현은
 * 삭제됐다). 판정 기준은 분리 기대값(expectedZoneName·expectedZoneCell) 직접 비교이고,
 * 하위 호환으로 남은 통짜 expected 와의 정합은 별도 가드 테스트가 지킨다 (D-4).
 * 근거 결정: docs/MSG-234.md 의 §D2(명명 산술)·§D4(폴백)·§D5(겹침 결정성), docs/MSG-259.md 의 D-4(픽스처),
 * docs/MSG-341.md 의 D-3·D-4.
 */
@DisplayName("격자 표시명 명명 계약 (픽스처 zone-naming.json 이 실행형 정본)")
class ZoneNamingContractTest {

	private record ZoneRect(String zoneKey, String name, int minGridY, int maxGridY,
		int minGridX, int maxGridX, int priority) {
	}

	private record NamingCase(String name, String gridId, List<String> zones, String regionName, String expected,
		String expectedZoneName, String expectedZoneCell) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Fixture(Map<String, ZoneRect> zones, List<NamingCase> cases) {
	}

	private static Fixture loadFixture() {
		try (InputStream in = ZoneNamingContractTest.class.getResourceAsStream("/fixtures/zone-naming.json")) {
			assertThat(in).as("픽스처 파일(/fixtures/zone-naming.json) 존재").isNotNull();
			return new ObjectMapper().readValue(in, Fixture.class);
		} catch (IOException e) {
			throw new IllegalStateException("픽스처 로드 실패", e);
		}
	}

	/** 케이스가 활성화한 zone 키들을 실 엔티티로 세워 리졸버를 만든다 (정렬·타이브레이크는 리졸버 책임). */
	private static ZoneNameResolver resolverOf(Fixture fixture, NamingCase namingCase) {
		List<Zone> zones = namingCase.zones().stream()
			.map(key -> {
				ZoneRect rect = fixture.zones().get(key);
				assertThat(rect).as("케이스 [%s] 가 참조한 zone 키 [%s] 미정의", namingCase.name(), key).isNotNull();
				return Zone.builder()
					.zoneKey(rect.zoneKey())
					.name(rect.name())
					.minGridY(rect.minGridY())
					.maxGridY(rect.maxGridY())
					.minGridX(rect.minGridX())
					.maxGridX(rect.maxGridX())
					.priority(rect.priority())
					.build();
			})
			.toList();
		return new ZoneNameResolver(zones);
	}

	@Test
	@DisplayName("픽스처 케이스가 비어 있으면 실패한다 (파일 누락·파싱 실패가 조용히 0건 통과로 새지 않게)")
	void 픽스처_케이스가_비어있으면_테스트가_실패한다() {
		Fixture fixture = loadFixture();
		assertThat(fixture.zones()).isNotEmpty();
		assertThat(fixture.cases()).isNotEmpty();
	}

	@TestFactory
	@DisplayName("픽스처 전 케이스: resolver.name(gridY, gridX) == (expectedZoneName, expectedZoneCell)")
	Stream<DynamicTest> 픽스처_케이스를_전부_검증한다() {
		Fixture fixture = loadFixture();
		return fixture.cases().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
			GridIndex index = GridEncoder.decode(c.gridId());

			ZoneCellName actual = resolverOf(fixture, c).name(index.gridY(), index.gridX());

			assertThat(actual).isEqualTo(new ZoneCellName(c.expectedZoneName(), c.expectedZoneCell()));
		}));
	}

	@Test
	@DisplayName("분리 기대값과 통짜 expected 가 어긋나면 실패한다 (픽스처 자체 모순 가드)")
	void 픽스처의_분리_기대값과_통짜_기대값이_어긋나면_실패한다() {
		for (NamingCase c : loadFixture().cases()) {
			if (c.expectedZoneName() != null) {
				assertThat(c.expected()).as("케이스 [%s]: 통짜 표시명은 두 분리 기대값의 조립이어야 한다", c.name())
					.isEqualTo(c.expectedZoneName() + " " + c.expectedZoneCell());
				continue;
			}
			assertThat(c.expectedZoneCell()).as("케이스 [%s]: zoneName 이 없으면 zoneCell 도 없어야 한다(항상 쌍)", c.name())
				.isNull();
			assertThat(c.expected()).as("케이스 [%s]: 구역 밖이면 통짜 표시명은 행정동 폴백이거나 없어야 한다", c.name())
				.isIn(c.regionName(), null);
		}
	}

	@Test
	@DisplayName("구역 밖 격자는 NONE 을 반환한다 — null 이 아니라 소비처가 분기 없이 두 필드를 옮긴다")
	void 구역_밖_격자는_NONE을_반환하고_null이_아니다() {
		ZoneNameResolver resolver = new ZoneNameResolver(List.of(ZoneTestFixtures.zone("naming-out", "구역밖", 0)));

		ZoneCellName name = resolver.name(ZoneTestFixtures.MAX_GRID_Y + 1, ZoneTestFixtures.MIN_GRID_X);

		assertThat(name).isEqualTo(ZoneCellName.NONE);
		assertThat(name.zoneName()).isNull();
		assertThat(name.zoneCell()).isNull();
	}

	@Test
	@DisplayName("zone 이 0건인 리졸버도 전 격자를 NONE 으로 계산한다 (시딩 전에도 응답이 폴백으로 동작)")
	void 리졸버는_구역이_0건이어도_전_격자를_NONE으로_계산한다() {
		ZoneNameResolver resolver = new ZoneNameResolver(List.of());

		assertThat(resolver.name(ZoneTestFixtures.MIN_GRID_Y, ZoneTestFixtures.MIN_GRID_X))
			.isEqualTo(ZoneCellName.NONE);
		assertThat(resolver.name(0, 0)).isEqualTo(ZoneCellName.NONE);
	}
}
