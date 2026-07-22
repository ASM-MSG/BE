package com.msg.fillmap.usergrid.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.usergrid.repository.CollectionGridProjection;
import com.msg.fillmap.usergrid.repository.CollectionSummaryProjection;
import com.msg.fillmap.usergrid.repository.UserGridRepository;
import com.msg.fillmap.usergrid.service.impl.UserGridQueryServiceImpl;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserGridQueryServiceImpl")
class UserGridQueryServiceImplTest {

	@Mock
	private UserGridRepository userGridRepository;

	@Mock
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	@InjectMocks
	private UserGridQueryServiceImpl userGridQueryService;

	@Nested
	@DisplayName("getCollectionSummary")
	class GetCollectionSummary {

		@Test
		@DisplayName("요약은 점령격자수 영상총합 방문행정동수를 담은 뷰를 반환한다")
		void 요약은_점령격자수_영상총합_방문행정동수를_담은_뷰를_반환한다() {
			given(userGridRepository.getCollectionSummary(1L))
				.willReturn(projection(15, 42L, 6));

			CollectionSummaryView view = userGridQueryService.getCollectionSummary(1L);

			assertThat(view.totalGridCount()).isEqualTo(15);
			assertThat(view.totalVideoCount()).isEqualTo(42L);
			assertThat(view.visitedRegionCount()).isEqualTo(6);
		}

		@Test
		@DisplayName("점령이 없으면 세 집계가 모두 0인 뷰를 반환한다")
		void 점령이_없으면_세_집계가_모두_0인_뷰를_반환한다() {
			given(userGridRepository.getCollectionSummary(2L))
				.willReturn(projection(0, 0L, 0));

			CollectionSummaryView view = userGridQueryService.getCollectionSummary(2L);

			assertThat(view).isEqualTo(new CollectionSummaryView(0, 0L, 0));
		}
	}

	@Nested
	@DisplayName("getCollectionGrids")
	class GetCollectionGrids {

		@Test
		@DisplayName("gridY gridX는 gridId를 디코드한 값이다")
		void gridY_gridX는_gridId를_디코드한_값이다() {
			String gridId = "41642_110458";
			given(userGridRepository.getCollectionGrids(1L))
				.willReturn(List.of(gridProjection(gridId, null, null)));

			CollectionGridView view = userGridQueryService.getCollectionGrids(1L).get(0);

			GridIndex decoded = GridEncoder.decode(gridId);
			assertThat(view.gridId()).isEqualTo(gridId);
			assertThat(view.gridY()).isEqualTo((int) decoded.gridY());
			assertThat(view.gridX()).isEqualTo((int) decoded.gridX());
		}

		@Test
		@DisplayName("coverThumbnailUrl은 썸네일key가 있으면 presigned GET URL이다")
		void coverThumbnailUrl은_썸네일key가_있으면_presigned_GET_URL이다() {
			String thumbKey = "videos/thumb/1042.jpg";
			String signed = "https://s3.example/thumb.jpg?X-Amz-Signature=abc";
			given(userGridRepository.getCollectionGrids(1L))
				.willReturn(List.of(gridProjection("41642_110458", 1042L, thumbKey)));
			given(thumbnailUrlPresigner.presign(thumbKey)).willReturn(signed);

			CollectionGridView view = userGridQueryService.getCollectionGrids(1L).get(0);

			assertThat(view.coverVideoId()).isEqualTo(1042L);
			assertThat(view.coverThumbnailUrl()).isEqualTo(signed);
		}

		@Test
		@DisplayName("coverThumbnailUrl은 cover가 null이면 null이다")
		void coverThumbnailUrl은_cover가_null이면_null이다() {
			given(userGridRepository.getCollectionGrids(1L))
				.willReturn(List.of(gridProjection("41642_110458", null, null)));

			CollectionGridView view = userGridQueryService.getCollectionGrids(1L).get(0);

			assertThat(view.coverVideoId()).isNull();
			assertThat(view.coverThumbnailUrl()).isNull();
		}
	}

	private CollectionGridProjection gridProjection(String gridId, Long coverVideoId, String coverThumbnailKey) {
		return new CollectionGridProjection() {
			@Override
			public String getGridId() {
				return gridId;
			}

			@Override
			public LocalDateTime getFirstCollectedAt() {
				return LocalDateTime.of(2026, 7, 20, 18, 3, 11);
			}

			@Override
			public LocalDateTime getLastUploadedAt() {
				return LocalDateTime.of(2026, 7, 21, 9, 12, 0);
			}

			@Override
			public Integer getVideoCount() {
				return 3;
			}

			@Override
			public Long getCoverVideoId() {
				return coverVideoId;
			}

			@Override
			public String getCoverThumbnailKey() {
				return coverThumbnailKey;
			}
		};
	}

	private CollectionSummaryProjection projection(int totalGridCount, long totalVideoCount, int visitedRegionCount) {
		return new CollectionSummaryProjection() {
			@Override
			public Integer getTotalGridCount() {
				return totalGridCount;
			}

			@Override
			public Long getTotalVideoCount() {
				return totalVideoCount;
			}

			@Override
			public Integer getVisitedRegionCount() {
				return visitedRegionCount;
			}
		};
	}
}
