package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionQueryService.MentionedRegionMatch;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto.MentionedAreaDto;
import com.msg.fillmap.zone.dto.ZoneResponseDto;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * 언급 지역 신호 판정 단위 검증 (MSG-468 §도메인 로직 1~3). 데이터 출처 두 계약(zones·regions)은 mock —
 * 2단 판정(통칭 우선)·유일성 규칙·종류 판정·담김 비율 산술·구역 WGS84 환산이 검증 대상이다.
 * 행정구역 쪽 토큰 대조와 접미 보정(축약형_시도_이름도_정식_명칭으로_대조된다)은 SQL 구현이라
 * Owner A(RegionQueryServiceImpl) 테스트 몫이다.
 */
@DisplayName("RouteMentionedAreaResolver — 언급 지역 신호 판정")
class RouteMentionedAreaResolverTest {

	private static final ViewportBounds 서울_뷰포트 = new ViewportBounds(37.45, 126.85, 37.65, 127.10);

	private final ZoneQueryService zoneQueryService = mock(ZoneQueryService.class);
	private final RegionQueryService regionQueryService = mock(RegionQueryService.class);
	private final RouteMentionedAreaResolver resolver =
		new RouteMentionedAreaResolver(zoneQueryService, regionQueryService);

	/* ---------- 픽스처 ---------- */

	/** zone 서면(부산 상권) — 격자 사각형 17행 × 11열 (약 1.7km × 1.1km, 남북 26행 캡 안). */
	private static ZoneResponseDto 서면(String zoneKey) {
		return new ZoneResponseDto(zoneKey, "서면", null, 16850, 16866, 11414, 11424, 0);
	}

	private static MentionedRegionMatch 부산_매칭(boolean overlapsViewport) {
		return new MentionedRegionMatch("부산광역시", 35.1985, 129.0538,
			35.0512, 128.7602, 35.3891, 129.2723, overlapsViewport);
	}

	/* ---------- 시나리오 ---------- */

	@Nested
	@DisplayName("이름 대조 — 2단 판정 (§도메인 로직 1)")
	class Matching {

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("동명 읍·면이 있어도 통칭이 우선한다 — zone 유일 일치면 행정구역 동명 그룹은 세지 않는다")
		void 동명_읍면이_있어도_통칭이_우선한다() {
			given(zoneQueryService.getZones()).willReturn(List.of(서면("seomyeon")));

			MentionedAreaDto area = resolver.resolve("서면", 서울_뷰포트);

			assertThat(area.name()).isEqualTo("서면");
			assertThat(area.kind()).isEqualTo("MOVE");	// 서울 뷰포트와 부산 구역은 안 겹친다
			// 춘천·홍천·양양의 면 단위 동명 그룹이 있어도 조회 자체를 하지 않는다 — 평면 유일성이면 늘 무신호였다.
			then(regionQueryService).shouldHaveNoInteractions();
		}

		// 검증: FR-ROUTE-14
		@Test
		void 지역을_말하지_않으면_신호가_없다() {
			assertThat(resolver.resolve(null, 서울_뷰포트)).isNull();
			assertThat(resolver.resolve("   ", 서울_뷰포트)).isNull();
			then(zoneQueryService).shouldHaveNoInteractions();	// 무언급은 대조 없이 끝난다
			then(regionQueryService).shouldHaveNoInteractions();
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("동명 행정구역이 여럿이면 신호가 없다 — 하나가 화면 안에 겹치는 배치도 무신호")
		void 동명_행정구역이_여럿이면_신호가_없다() {
			given(regionQueryService.matchMentionedRegions(eq("중구"), any())).willReturn(List.of(
				new MentionedRegionMatch("중구", 37.56, 126.99, 37.52, 126.95, 37.60, 127.03, true),
				new MentionedRegionMatch("중구", 35.11, 129.03, 35.08, 129.00, 35.14, 129.06, false)));

			assertThat(resolver.resolve("중구", 서울_뷰포트)).isNull();
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("동명 통칭이 여럿이면 신호가 없다 — 행정구역 대조로 넘어가지도 않는다 (2단 판정 1)")
		void 동명_통칭이_여럿이면_신호가_없다() {
			given(zoneQueryService.getZones()).willReturn(List.of(서면("seomyeon"), 서면("seomyeon-2")));

			assertThat(resolver.resolve("서면", 서울_뷰포트)).isNull();
			then(regionQueryService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("종류 판정 — 겹침과 담김 비율 (§도메인 로직 2~3)")
	class KindDecision {

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("좁은 화면에서 넓은 지역을 말하면 축소 신호가 실린다 — trim 후 대조된다")
		void 좁은_화면에서_넓은_지역을_말하면_축소_신호가_실린다() {
			// 부산역 도보 화면 (한 변 약 1km) — 부산 외접 사각형(0.34도 × 0.51도)의 0.5% 미만만 담는다.
			ViewportBounds 부산역_도보 = new ViewportBounds(35.110, 129.036, 35.120, 129.046);
			given(regionQueryService.matchMentionedRegions(eq("부산"), any())).willReturn(List.of(부산_매칭(true)));

			MentionedAreaDto area = resolver.resolve(" 부산 ", 부산역_도보);

			assertThat(area.kind()).isEqualTo("ZOOM_OUT");
			assertThat(area.name()).isEqualTo("부산광역시");
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("화면이 언급 지역을 충분히 담으면 신호가 없다 — 담김 비율 25% 경계 양쪽")
		void 화면이_언급_지역을_충분히_담으면_신호가_없다() {
			// 지역 외접 사각형 0.5도 × 0.5도 — 값은 전부 이진 소수라 비율이 정확히 떨어진다.
			MentionedRegionMatch 지역 = new MentionedRegionMatch("가상시", 35.25, 129.25,
				35.0, 129.0, 35.5, 129.5, true);
			given(regionQueryService.matchMentionedRegions(any(), any())).willReturn(List.of(지역));

			// 정확히 25% 담김 (0.25 × 0.25 / 0.5 × 0.5) — 임계 이상이라 무신호다.
			assertThat(resolver.resolve("가상시", new ViewportBounds(35.0, 129.0, 35.25, 129.25))).isNull();

			// 경도만 살짝 좁혀 25% 미만 (0.25 × 0.234375 / 0.25 = 23.4%) — 축소 신호가 뜬다.
			MentionedAreaDto area = resolver.resolve("가상시", new ViewportBounds(35.0, 129.0, 35.25, 129.234375));
			assertThat(area.kind()).isEqualTo("ZOOM_OUT");
		}

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("실제 경계와 겹치지 않으면 외접 사각형이 겹쳐도 이동 신호다 — 김해 뷰포트에 부산")
		void 실제_경계와_겹치지_않으면_외접_사각형이_겹쳐도_이동_신호다() {
			// 김해 뷰포트 — 부산 외접 사각형 안에 들지만 실제 경계(overlapsViewport=false)와는 안 겹친다.
			ViewportBounds 김해 = new ViewportBounds(35.20, 128.85, 35.28, 128.95);
			given(regionQueryService.matchMentionedRegions(eq("부산"), any())).willReturn(List.of(부산_매칭(false)));

			MentionedAreaDto area = resolver.resolve("부산", 김해);

			// 외접 사각형으로 판정했다면 ZOOM_OUT 이 나갔을 배치 — 행정구역 겹침은 계약의 실제 경계 판정이다.
			assertThat(area.kind()).isEqualTo("MOVE");
		}
	}

	@Nested
	@DisplayName("범위 재료 — 외접 사각형 상시 동봉 (FR-4)")
	class BoundingBox {

		// 검증: FR-ROUTE-14
		@Test
		@DisplayName("신호에는 외접 사각형이 항상 실린다 — 행정구역은 계약값 그대로, 구역은 격자 환산")
		void 신호에는_외접_사각형이_항상_실린다() {
			// MOVE (행정구역, ST_Extent 출처) — 판정에 쓴 계약의 사각형 그대로가 응답에 실린다.
			given(regionQueryService.matchMentionedRegions(eq("부산"), any())).willReturn(List.of(부산_매칭(false)));
			MentionedAreaDto 행정구역 = resolver.resolve("부산", 서울_뷰포트);
			assertThat(행정구역.minLat()).isEqualTo(35.0512);
			assertThat(행정구역.minLng()).isEqualTo(128.7602);
			assertThat(행정구역.maxLat()).isEqualTo(35.3891);
			assertThat(행정구역.maxLng()).isEqualTo(129.2723);

			// ZOOM_OUT (구역, 격자 사각형 환산 출처) — 500m 화면이면 서면(약 1.7km × 1.1km)의 13% 수준이다.
			given(zoneQueryService.getZones()).willReturn(List.of(서면("seomyeon")));
			GridPoint 구역_중앙 = GridEncoder.center("16858_11419");
			ViewportBounds 좁은_화면 = new ViewportBounds(구역_중앙.lat() - 0.002, 구역_중앙.lon() - 0.002,
				구역_중앙.lat() + 0.002, 구역_중앙.lon() + 0.002);
			MentionedAreaDto 구역 = resolver.resolve("서면", 좁은_화면);
			assertThat(구역.kind()).isEqualTo("ZOOM_OUT");
			// 환산 사각형은 네 모서리 셀의 바깥 꼭짓점까지 덮는다 — 모서리 셀 중심보다 항상 바깥이다.
			GridPoint 남서셀_중심 = GridEncoder.center("16850_11414");
			GridPoint 북동셀_중심 = GridEncoder.center("16866_11424");
			assertThat(구역.minLat()).isLessThan(남서셀_중심.lat());
			assertThat(구역.minLng()).isLessThan(남서셀_중심.lon());
			assertThat(구역.maxLat()).isGreaterThan(북동셀_중심.lat());
			assertThat(구역.maxLng()).isGreaterThan(북동셀_중심.lon());
			// 중심은 환산 사각형의 중점이다 (구역은 무게중심이 따로 없다 — §API 명세).
			assertThat(구역.centerLat()).isEqualTo((구역.minLat() + 구역.maxLat()) / 2);
			assertThat(구역.centerLng()).isEqualTo((구역.minLng() + 구역.maxLng()) / 2);
		}
	}
}
