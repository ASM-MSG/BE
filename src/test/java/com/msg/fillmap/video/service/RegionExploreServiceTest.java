package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.video.dto.ExploreSort;
import com.msg.fillmap.video.dto.RegionExploreResponseDto;
import com.msg.fillmap.video.dto.RegionGridCountResponseDto;
import com.msg.fillmap.video.repository.ExploreGridProjection;
import com.msg.fillmap.video.repository.RegionExploreSummaryProjection;
import com.msg.fillmap.video.repository.RegionGridCountProjection;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 전역 탐색 서비스 (MSG-238 §도메인 4). 게이트·정렬·카운트 정의는 repository 계약이라 RegionExploreQueryTest 가
 * 실 DB 로 검증하고, 여기서는 sort 분기·limit 보정(§D2)·커버 presign(null→null 통과)·미존재 regionCode 합성
 * (regionName null·0·빈 배열 — 예외 아님, §D2)·DTO 매핑만 본다. HTTP 200/400/401 은 컨트롤러 테스트 담당.
 * 구역 이름(MSG-341)은 리졸버를 목이 아니라 실물로 준다 — 명명 산술 자체의 정본은 픽스처 계약 테스트이고
 * 여기서 볼 것은 "요청당 1회 받아 카드마다 꽂는다"는 배선이라, 실물이 배선 오류를 그대로 드러낸다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegionExploreServiceImpl")
class RegionExploreServiceTest {

	private static final String REGION_CODE = "2644056000";

	@Mock
	private VideoRepository videoRepository;

	@Mock
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	@Mock
	private ZoneNameQueryService zoneNameQueryService;

	@InjectMocks
	private RegionExploreServiceImpl regionExploreService;

	/** 카드 좌표(38879_112390)를 덮는 구역 하나 — 행 A 는 maxGridY, 열 1 은 minGridX 다. */
	private static final Zone SEOMYEON = Zone.builder()
		.zoneKey("seomyeon").name("서면")
		.minGridY(38870).maxGridY(38884).minGridX(112385).maxGridX(112400)
		.priority(0)
		.build();

	private ExploreGridProjection cardProjection(String gridId, long gridY, long gridX, int videoCount,
		String coverThumbnailKey) {
		return new ExploreGridProjection() {
			@Override
			public String getGridId() {
				return gridId;
			}

			@Override
			public Long getGridY() {
				return gridY;
			}

			@Override
			public Long getGridX() {
				return gridX;
			}

			@Override
			public Integer getVideoCount() {
				return videoCount;
			}

			@Override
			public String getCoverThumbnailKey() {
				return coverThumbnailKey;
			}

			@Override
			public Short getCoverDurationSec() {
				return (short) 12;
			}
		};
	}

	private RegionExploreSummaryProjection summaryProjection(String regionName, int gridCount, long videoCount) {
		return new RegionExploreSummaryProjection() {
			@Override
			public String getRegionName() {
				return regionName;
			}

			@Override
			public Integer getGridCount() {
				return gridCount;
			}

			@Override
			public Long getVideoCount() {
				return videoCount;
			}
		};
	}

	private RegionGridCountProjection countProjection(String regionCode, String regionName, int gridCount) {
		return new RegionGridCountProjection() {
			@Override
			public String getRegionCode() {
				return regionCode;
			}

			@Override
			public String getRegionName() {
				return regionName;
			}

			@Override
			public Integer getGridCount() {
				return gridCount;
			}
		};
	}

	private void givenSummary(String regionName, int gridCount, long videoCount) {
		given(videoRepository.getRegionExploreSummary(REGION_CODE))
			.willReturn(Optional.of(summaryProjection(regionName, gridCount, videoCount)));
	}

	@Nested
	@DisplayName("getRegionGrids — 행정동 격자 카드 + 헤더 카운트")
	class GetRegionGrids {

		@BeforeEach
		void givenSeomyeonZone() {
			given(zoneNameQueryService.resolver()).willReturn(new ZoneNameResolver(List.of(SEOMYEON)));
		}

		@Test
		@DisplayName("sort가 POPULAR면 인기순 메서드를 선택한다")
		void sort가_POPULAR면_인기순_메서드를_선택한다() {
			given(videoRepository.findExploreGridsPopular(REGION_CODE, null)).willReturn(List.of());
			givenSummary("부전2동", 0, 0L);

			regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.POPULAR, null);

			then(videoRepository).should().findExploreGridsPopular(REGION_CODE, null);
			then(videoRepository).should(never()).findExploreGridsLatest(anyString(), any());
		}

		@Test
		@DisplayName("sort가 LATEST면 최신순 메서드를 선택한다")
		void sort가_LATEST면_최신순_메서드를_선택한다() {
			given(videoRepository.findExploreGridsLatest(REGION_CODE, null)).willReturn(List.of());
			givenSummary("부전2동", 0, 0L);

			regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.LATEST, null);

			then(videoRepository).should().findExploreGridsLatest(REGION_CODE, null);
			then(videoRepository).should(never()).findExploreGridsPopular(anyString(), any());
		}

		@Test
		@DisplayName("limit이 null이면 보정 없이 전부 조회한다")
		void limit이_null이면_보정_없이_전부_조회한다() {
			given(videoRepository.findExploreGridsPopular(REGION_CODE, null)).willReturn(List.of());
			givenSummary("부전2동", 0, 0L);

			regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.POPULAR, null);

			then(videoRepository).should().findExploreGridsPopular(REGION_CODE, null);
		}

		@Test
		@DisplayName("limit이 1미만이면 1로 보정해 조회한다")
		void limit이_1미만이면_1로_보정해_조회한다() {
			given(videoRepository.findExploreGridsPopular(REGION_CODE, 1)).willReturn(List.of());
			givenSummary("부전2동", 0, 0L);

			regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.POPULAR, 0);
			regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.POPULAR, -3);

			then(videoRepository).should(times(2)).findExploreGridsPopular(REGION_CODE, 1);
		}

		@Test
		@DisplayName("limit이 1이상이면 그대로 전달한다")
		void limit이_1이상이면_그대로_전달한다() {
			given(videoRepository.findExploreGridsPopular(REGION_CODE, 3)).willReturn(List.of());
			givenSummary("부전2동", 0, 0L);

			regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.POPULAR, 3);

			then(videoRepository).should().findExploreGridsPopular(REGION_CODE, 3);
		}

		@Test
		@DisplayName("커버 key는 presigned URL로 바뀌고 null key는 null로 통과한다")
		void 커버_key는_presigned_URL로_바뀌고_null_key는_null로_통과한다() {
			String thumbKey = "videos/thumb/1042.jpg";
			String signed = "https://s3.example/thumb.jpg?X-Amz-Signature=abc";
			given(videoRepository.findExploreGridsPopular(REGION_CODE, null)).willReturn(List.of(
				cardProjection("38879_112390", 38879L, 112390L, 138, thumbKey),
				cardProjection("38880_112391", 38880L, 112391L, 97, null)));
			given(thumbnailUrlPresigner.presign(thumbKey)).willReturn(signed);
			givenSummary("부전2동", 2, 235L);

			RegionExploreResponseDto result =
				regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.POPULAR, null);

			assertThat(result.grids()).hasSize(2);
			assertThat(result.grids().get(0).coverThumbnailUrl()).isEqualTo(signed);
			assertThat(result.grids().get(1).coverThumbnailUrl()).isNull();
		}

		@Test
		@DisplayName("헤더는 summary 값을 담고 regionCode를 에코하며 카드 필드를 그대로 매핑한다")
		void 헤더는_summary_값을_담고_regionCode를_에코하며_카드_필드를_그대로_매핑한다() {
			given(videoRepository.findExploreGridsPopular(REGION_CODE, null)).willReturn(List.of(
				cardProjection("38879_112390", 38879L, 112390L, 138, null)));
			givenSummary("부산광역시 부산진구 부전2동", 5, 355L);

			RegionExploreResponseDto result =
				regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.POPULAR, null);

			assertThat(result.regionCode()).isEqualTo(REGION_CODE);
			assertThat(result.regionName()).isEqualTo("부산광역시 부산진구 부전2동");
			assertThat(result.gridCount()).isEqualTo(5);
			assertThat(result.videoCount()).isEqualTo(355L);
			assertThat(result.grids().get(0).gridId()).isEqualTo("38879_112390");
			assertThat(result.grids().get(0).gridY()).isEqualTo(38879L);
			assertThat(result.grids().get(0).gridX()).isEqualTo(112390L);
			assertThat(result.grids().get(0).videoCount()).isEqualTo(138);
			assertThat(result.grids().get(0).coverDurationSec()).isEqualTo((short) 12);
		}

		@Test
		@DisplayName("카드 리스트 항목에 구역 이름이 붙는다")
		void 카드_리스트_항목에_구역_이름이_붙는다() {
			// MSG-341 FR-1·FR-8: 구역 안 카드는 zoneName+zoneCell 을 얻고, 구역 밖 카드는 둘 다 null 이다.
			// 카드가 2장이어도 resolver() 는 1회 — 항목마다 zones 를 다시 읽는 N+1 이 아님을 고정한다.
			given(videoRepository.findExploreGridsPopular(REGION_CODE, null)).willReturn(List.of(
				cardProjection("38879_112390", 38879L, 112390L, 138, null),
				cardProjection("41642_110458", 41642L, 110458L, 12, null)));
			givenSummary("부전2동", 2, 150L);

			RegionExploreResponseDto result =
				regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.POPULAR, null);

			// maxGridY(38884) − 38879 = 5 → 'F', 112390 − minGridX(112385) + 1 = 6
			assertThat(result.grids().get(0).zoneName()).isEqualTo("서면");
			assertThat(result.grids().get(0).zoneCell()).isEqualTo("F-6");
			assertThat(result.grids().get(1).zoneName()).isNull();
			assertThat(result.grids().get(1).zoneCell()).isNull();
			then(zoneNameQueryService).should(times(1)).resolver();
		}

		@Test
		@DisplayName("미존재 regionCode는 regionName null 카운트 0 빈 배열로 합성된다")
		void 미존재_regionCode는_regionName_null_카운트_0_빈_배열로_합성된다() {
			// §D2: 미존재 = 에러가 아니라 빈 결과(6404 재사용 안 함) — summary 0행을 서비스가 합성한다. HTTP 200 은 컨트롤러 몫.
			given(videoRepository.findExploreGridsPopular(REGION_CODE, null)).willReturn(List.of());
			given(videoRepository.getRegionExploreSummary(REGION_CODE)).willReturn(Optional.empty());

			RegionExploreResponseDto result =
				regionExploreService.getRegionGrids(REGION_CODE, ExploreSort.POPULAR, null);

			assertThat(result.regionCode()).isEqualTo(REGION_CODE);
			assertThat(result.regionName()).isNull();
			assertThat(result.gridCount()).isZero();
			assertThat(result.videoCount()).isZero();
			assertThat(result.grids()).isEmpty();
		}
	}

	@Nested
	@DisplayName("getExploreRegions — 전체 지역 리스트")
	class GetExploreRegions {

		@Test
		@DisplayName("전체 지역 행을 순서 그대로 DTO로 매핑한다")
		void 전체_지역_행을_순서_그대로_DTO로_매핑한다() {
			given(videoRepository.getRegionGridCounts()).willReturn(List.of(
				countProjection("2644056000", "부산광역시 부산진구 부전2동", 5),
				countProjection("1168051500", "서울특별시 강남구 역삼1동", 3)));

			List<RegionGridCountResponseDto> result = regionExploreService.getExploreRegions();

			assertThat(result).containsExactly(
				new RegionGridCountResponseDto("2644056000", "부산광역시 부산진구 부전2동", 5),
				new RegionGridCountResponseDto("1168051500", "서울특별시 강남구 역삼1동", 3));
		}

		@Test
		@DisplayName("전역 콘텐츠가 없으면 빈 리스트다")
		void 전역_콘텐츠가_없으면_빈_리스트다() {
			given(videoRepository.getRegionGridCounts()).willReturn(List.of());

			assertThat(regionExploreService.getExploreRegions()).isEmpty();
		}
	}
}
