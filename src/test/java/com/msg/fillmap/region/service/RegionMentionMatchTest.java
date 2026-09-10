package com.msg.fillmap.region.service;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.service.RegionQueryService.MentionedRegionMatch;

/**
 * RegionQueryService.matchMentionedRegions 통합 (MSG-468 · 실 PostGIS · 합성 폴리곤 · 롤백 격리).
 * 공유 로컬 DB 실데이터(3,558행)와 절대 충돌하지 않도록 실존하지 않는 9x 대역 시도 코드와 가공의
 * 해양생물 지명, 서해 공해상 소형 폴리곤만 쓴다(RegionReverseGeocodeTest 선례).
 */
// 검증: FR-ROUTE-14
@SpringBootTest
@Transactional
@DisplayName("RegionQueryService.matchMentionedRegions (합성 폴리곤 · 롤백 격리)")
class RegionMentionMatchTest {

	// 합성 폴리곤과 겹치지 않는 남서해 공해상 원거리 뷰포트 (겹침 판정 무발화 기준선).
	private static final ViewportBounds FAR_VIEWPORT = new ViewportBounds(33.0, 124.0, 33.01, 124.01);

	@Autowired
	private RegionQueryService regionQueryService;

	@Autowired
	private RegionRepository regionRepository;

	private void seed(String code, String fullName, double minLon, double minLat, double maxLon, double maxLat) {
		regionRepository.upsert(code, fullName, code.substring(0, 5),
			rectanglePolygonJson(minLon, minLat, maxLon, maxLat), CELL_AREA_M2);
	}

	/** 시도 99 대역 — 동 2행이 코드 접두 2로 한 그룹에 묶인다 (동편이 서편에 변을 공유하는 등면적 사각형). */
	private void seedMalmijalSido() {
		seed("9911100001", "말미잘광역시 말미잘구 말미잘1동", 124.99, 35.99, 125.01, 36.01);
		seed("9911100002", "말미잘광역시 말미잘구 말미잘2동", 125.01, 35.99, 125.03, 36.01);
	}

	@Test
	@DisplayName("축약형 시도 이름은 행정 접미 보정으로 정식 명칭 그룹에 닿는다")
	void 축약형_시도는_정식_명칭으로_대조된다() {
		seedMalmijalSido();

		List<MentionedRegionMatch> abbreviated = regionQueryService.matchMentionedRegions("말미잘", FAR_VIEWPORT);
		List<MentionedRegionMatch> fullName = regionQueryService.matchMentionedRegions("말미잘광역시", FAR_VIEWPORT);

		assertThat(abbreviated).hasSize(1);
		assertThat(abbreviated.get(0).name()).isEqualTo("말미잘광역시");
		assertThat(fullName).hasSize(1);
		assertThat(fullName.get(0).name()).isEqualTo("말미잘광역시");
	}

	@Test
	@DisplayName("동명 시군구는 시도별 코드 접두 그룹으로 전부 반환된다")
	void 동명_시군구_그룹은_전부_반환된다() {
		seed("9722200001", "성게특별자치도 가리비구 성게1동", 124.99, 36.99, 125.01, 37.01);
		seed("9811100001", "해삼광역시 가리비구 해삼1동", 124.99, 35.99, 125.01, 36.01);

		List<MentionedRegionMatch> matches = regionQueryService.matchMentionedRegions("가리비구", FAR_VIEWPORT);

		assertThat(matches).hasSize(2);
		assertThat(matches).allSatisfy(match -> assertThat(match.name()).isEqualTo("가리비구"));
	}

	@Test
	@DisplayName("그룹 전체의 외접 사각형(ST_Extent)과 무게중심(ST_Centroid)이 실린다")
	void 외접_사각형과_무게중심이_실린다() {
		seedMalmijalSido();

		List<MentionedRegionMatch> matches = regionQueryService.matchMentionedRegions("말미잘", FAR_VIEWPORT);

		assertThat(matches).hasSize(1);
		MentionedRegionMatch match = matches.get(0);
		assertThat(match.minLat()).isCloseTo(35.99, within(1e-6));
		assertThat(match.minLng()).isCloseTo(124.99, within(1e-6));
		assertThat(match.maxLat()).isCloseTo(36.01, within(1e-6));
		assertThat(match.maxLng()).isCloseTo(125.03, within(1e-6));
		// 등면적 사각형 2개의 면적 가중 중심 = 공유 변 중점.
		assertThat(match.centerLat()).isCloseTo(36.0, within(1e-6));
		assertThat(match.centerLng()).isCloseTo(125.01, within(1e-6));
	}

	@Test
	@DisplayName("겹침은 외접 사각형이 아니라 실제 경계 기준이다 (김해/부산류 케이스)")
	void 외접_사각형이_겹쳐도_실경계가_안_겹치면_겹침이_아니다() {
		// 사이가 빈(124.99~125.00 · 125.02~125.03) 두 사각형 — 외접 사각형은 틈(125.00~125.02)을 덮는다.
		seed("9811100001", "해삼광역시 해삼구 해삼1동", 124.99, 35.99, 125.00, 36.01);
		seed("9811100002", "해삼광역시 해삼구 해삼2동", 125.02, 35.99, 125.03, 36.01);
		ViewportBounds gapViewport = new ViewportBounds(35.995, 125.005, 36.005, 125.015);
		ViewportBounds touchingViewport = new ViewportBounds(35.995, 124.995, 36.005, 125.005);

		List<MentionedRegionMatch> inGap = regionQueryService.matchMentionedRegions("해삼", gapViewport);
		List<MentionedRegionMatch> touching = regionQueryService.matchMentionedRegions("해삼", touchingViewport);

		assertThat(inGap).hasSize(1);
		// 뷰포트가 외접 사각형 안에 완전히 들어 있어도(min/max 확인) 실경계와는 안 겹친다.
		assertThat(inGap.get(0).minLng()).isLessThan(125.005);
		assertThat(inGap.get(0).maxLng()).isGreaterThan(125.015);
		assertThat(inGap.get(0).overlapsViewport()).isFalse();
		assertThat(touching).hasSize(1);
		assertThat(touching.get(0).overlapsViewport()).isTrue();
	}
}
