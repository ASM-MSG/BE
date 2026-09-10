package com.msg.fillmap.mission.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionView;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 코스 스팟 표시 이름 결정 사다리 (MSG-492 §도메인 1, 테스트 13~17).
 * 순수 로직이라 DB 없이 돈다 — zones 는 실제 ZoneNameResolver 에 사각형을 넣어 만들고, 행정동만 목으로 세운다.
 */
// 검증: FR-MISSION-19
class CourseSpotNameResolverTest {

	/** 부산 서면 일대 실제 격자 (EPSG:5179 인덱스) — KoreaCoordinates 범위 안이라 행정동 조회까지 간다. */
	private static final String GRID_ID = "16752_11357";

	private final RegionQueryService regionQueryService = mock(RegionQueryService.class);

	@Test
	void 명소_이름이_있으면_그대로_쓴다() {
		CourseSpotNameResolver resolver = new CourseSpotNameResolver(emptyZones(), regionQueryService);

		assertThat(resolver.resolve(GRID_ID, "광안리해수욕장")).isEqualTo("광안리해수욕장");
	}

	@Test
	void 이름이_이미_있으면_구역과_행정동_판정이_돌지_않는다() {
		CourseSpotNameResolver resolver = new CourseSpotNameResolver(emptyZones(), regionQueryService);

		resolver.resolve(GRID_ID, "광안리해수욕장");

		verifyNoInteractions(regionQueryService);
	}

	@Test
	void 이름이_없고_구역_안이면_구역명과_칸번호를_붙인다() {
		// 격자 하나를 덮는 사각형 — maxGridY 가 곧 행 A 라 이 격자는 A-1 이다.
		ZoneNameResolver zones = new ZoneNameResolver(List.of(zone("서면", 16752, 16752, 11357, 11357)));
		CourseSpotNameResolver resolver = new CourseSpotNameResolver(zones, regionQueryService);

		assertThat(resolver.resolve(GRID_ID, null)).isEqualTo("서면 A-1");
		verifyNoInteractions(regionQueryService);
	}

	@Test
	void 이름이_없고_구역_밖이면_행정동_이름의_마지막_토큰만_쓴다() {
		given(regionQueryService.resolveByPoint(anyDouble(), anyDouble()))
			.willReturn(Optional.of(new RegionView("2638060100", "부산광역시 사하구 다대2동", "26380")));
		CourseSpotNameResolver resolver = new CourseSpotNameResolver(emptyZones(), regionQueryService);

		assertThat(resolver.resolve(GRID_ID, null)).isEqualTo("다대2동");
	}

	@Test
	void 품는_행정동이_없으면_최근접_행정동_이름을_쓴다() {
		given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.empty());
		given(regionQueryService.resolveNearestByPoint(anyDouble(), anyDouble()))
			.willReturn(Optional.of(new RegionView("2638060100", "부산광역시 사하구 다대2동", "26380")));
		CourseSpotNameResolver resolver = new CourseSpotNameResolver(emptyZones(), regionQueryService);

		assertThat(resolver.resolve(GRID_ID, null)).isEqualTo("다대2동");
	}

	@Test
	void 공백뿐인_명소_이름은_없는_것으로_보고_폴백으로_내려간다() {
		given(regionQueryService.resolveByPoint(anyDouble(), anyDouble()))
			.willReturn(Optional.of(new RegionView("2638060100", "부산광역시 사하구 다대2동", "26380")));
		CourseSpotNameResolver resolver = new CourseSpotNameResolver(emptyZones(), regionQueryService);

		assertThat(resolver.resolve(GRID_ID, "   ")).isEqualTo("다대2동");
	}

	@Test
	void 어느_단에서도_못_구하면_null_이다() {
		given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.empty());
		given(regionQueryService.resolveNearestByPoint(anyDouble(), anyDouble())).willReturn(Optional.empty());
		CourseSpotNameResolver resolver = new CourseSpotNameResolver(emptyZones(), regionQueryService);

		assertThat(resolver.resolve(GRID_ID, null)).isNull();
	}

	@Test
	void 서비스_범위_밖_격자는_행정동_조회_없이_null_이다() {
		CourseSpotNameResolver resolver = new CourseSpotNameResolver(emptyZones(), regionQueryService);

		// 음수 grid_y 는 5179 평면에서 한국 밖 — 계약이 INVALID_COORDINATE 를 던지므로 부르지 않는다.
		assertThat(resolver.resolve("-39200_112198", null)).isNull();
		verifyNoInteractions(regionQueryService);
	}

	@Test
	void 공백이_없는_행정동_이름은_원문_그대로_쓴다() {
		given(regionQueryService.resolveByPoint(anyDouble(), anyDouble()))
			.willReturn(Optional.of(new RegionView("2638060100", "다대2동", "26380")));
		CourseSpotNameResolver resolver = new CourseSpotNameResolver(emptyZones(), regionQueryService);

		assertThat(resolver.resolve(GRID_ID, null)).isEqualTo("다대2동");
	}

	private static ZoneNameResolver emptyZones() {
		return new ZoneNameResolver(List.of());
	}

	private static Zone zone(String name, int minY, int maxY, int minX, int maxX) {
		return Zone.builder()
			.zoneKey(name)
			.name(name)
			.minGridY(minY)
			.maxGridY(maxY)
			.minGridX(minX)
			.maxGridX(maxX)
			.priority(0)
			.build();
	}
}
