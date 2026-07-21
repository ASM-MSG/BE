package com.msg.fillmap.usergrid.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.usergrid.repository.CollectionSummaryProjection;
import com.msg.fillmap.usergrid.repository.UserGridRepository;
import com.msg.fillmap.usergrid.service.impl.UserGridQueryServiceImpl;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserGridQueryServiceImpl")
class UserGridQueryServiceImplTest {

	@Mock
	private UserGridRepository userGridRepository;

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
