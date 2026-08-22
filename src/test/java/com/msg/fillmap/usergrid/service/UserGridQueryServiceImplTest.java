package com.msg.fillmap.usergrid.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.ErrorCode;
import com.msg.fillmap.usergrid.dto.CollectionGridSort;
import com.msg.fillmap.usergrid.repository.CollectionGridProjection;
import com.msg.fillmap.usergrid.repository.CollectionSummaryProjection;
import com.msg.fillmap.usergrid.repository.FriendCollectionGridProjection;
import com.msg.fillmap.usergrid.repository.RegionVideoProjection;
import com.msg.fillmap.usergrid.repository.UploadHistoryProjection;
import com.msg.fillmap.usergrid.repository.UserGridRepository;
import com.msg.fillmap.usergrid.service.impl.UserGridQueryServiceImpl;
import com.msg.fillmap.usergrid.support.CollectionGridCursor;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserGridQueryServiceImpl")
class UserGridQueryServiceImplTest {

	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 22, 9, 30);

	/** 테스트 격자(19422_9582)를 덮는 구역 — 행 = 'A' + (19430 − 19422) = 'I', 열 = 9582 − 9574 + 1 = 9. */
	private static final Zone SEOMYEON = Zone.builder()
		.zoneKey("seomyeon").name("서면")
		.minGridY(19420).maxGridY(19430).minGridX(9574).maxGridX(9584)
		.priority(0)
		.build();

	@Mock
	private UserGridRepository userGridRepository;

	@Mock
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	@Mock
	private ZoneNameQueryService zoneNameQueryService;

	@InjectMocks
	private UserGridQueryServiceImpl userGridQueryService;

	/**
	 * 구역 이름 배선을 검증하는 목록 조회에서 호출한다 — 리졸버는 목이 아니라 실물이라 배선이 어긋나면
	 * 값이 그대로 틀린다. getCollectionSummary 는 격자 목록이 아니라 이 배선을 타지 않는다.
	 */
	private void givenSeomyeonZone() {
		given(zoneNameQueryService.resolver()).willReturn(new ZoneNameResolver(List.of(SEOMYEON)));
	}

	/** 파라미터 없는 기존 호출과 같은 조합 — 전국·수집 시각순이고 서비스가 상한 30을 채워 넣는다(FR-7). */
	private List<CollectionGridView> collectDefault() {
		return userGridQueryService.getCollectionGrids(1L, null, CollectionGridSort.COLLECTED, null);
	}

	@Nested
	@DisplayName("getCollectionSummary")
	class GetCollectionSummary {

		// 검증: FR-COLLECT-07, FR-STREAK-08
		@Test
		@DisplayName("요약은 점령격자수 영상총합 방문행정동수를 담은 뷰를 반환한다")
		void 요약은_점령격자수_영상총합_방문행정동수를_담은_뷰를_반환한다() {
			given(userGridRepository.getCollectionSummary(1L))
				.willReturn(projection(15, 42L, 6, 12, 21, 7));

			CollectionSummaryView view = userGridQueryService.getCollectionSummary(1L);

			assertThat(view.totalGridCount()).isEqualTo(15);
			assertThat(view.totalVideoCount()).isEqualTo(42L);
			assertThat(view.visitedRegionCount()).isEqualTo(6);
			assertThat(view.currentStreak()).isEqualTo(12);
			assertThat(view.maxStreak()).isEqualTo(21);
			assertThat(view.badgeCount()).isEqualTo(7);
		}

		// 검증: FR-COLLECT-07, FR-STREAK-08
		@Test
		@DisplayName("점령이 없으면 여섯 집계가 모두 0인 뷰를 반환한다")
		void 점령이_없으면_여섯_집계가_모두_0인_뷰를_반환한다() {
			given(userGridRepository.getCollectionSummary(2L))
				.willReturn(projection(0, 0L, 0, 0, 0, 0));

			CollectionSummaryView view = userGridQueryService.getCollectionSummary(2L);

			assertThat(view).isEqualTo(new CollectionSummaryView(0, 0L, 0, 0, 0, 0));
		}
	}

	@Nested
	@DisplayName("getUploadHistory")
	class GetUploadHistory {

		// 검증: FR-STREAK-08
		@Test
		@DisplayName("저장 존은 UTC 로 바인딩하고 프로젝션의 날짜와 건수를 그대로 담는다")
		void 저장_존은_UTC로_바인딩하고_프로젝션의_날짜와_건수를_그대로_담는다() {
			// D-4: storedZone 인자가 어긋나면 이 스텁이 매치되지 않아 테스트가 깨진다 — 존 배선 검증을 겸한다.
			// 기대값이 UTC 인 이유는 쓰기 경로(Video 생성자)가 UTC 고정이기 때문 (MSG-376 후속).
			given(userGridRepository.getUploadHistory(1L, ZoneOffset.UTC.getId()))
				.willReturn(List.of(historyProjection(LocalDate.of(2026, 8, 10), 3)));

			List<UploadHistoryView> views = userGridQueryService.getUploadHistory(1L);

			assertThat(views).containsExactly(new UploadHistoryView(LocalDate.of(2026, 8, 10), 3));
		}
	}

	@Nested
	@DisplayName("getCollectionGrids")
	class GetCollectionGrids {

		@BeforeEach
		void givenZone() {
			givenSeomyeonZone();
		}

		// 검증: FR-COLLECT-08
		@Test
		@DisplayName("gridY gridX는 gridId를 디코드한 값이다")
		void gridY_gridX는_gridId를_디코드한_값이다() {
			String gridId = "19422_9582";
			given(userGridRepository.getCollectionGrids(1L, null, "COLLECTED", 30))
				.willReturn(List.of(gridProjection(gridId, null, null)));

			CollectionGridView view = collectDefault().get(0);

			GridIndex decoded = GridEncoder.decode(gridId);
			assertThat(view.gridId()).isEqualTo(gridId);
			assertThat(view.gridY()).isEqualTo((int) decoded.gridY());
			assertThat(view.gridX()).isEqualTo((int) decoded.gridX());
		}

		// 검증: FR-COLLECT-08
		@Test
		@DisplayName("coverThumbnailUrl은 썸네일key가 있으면 presigned GET URL이다")
		void coverThumbnailUrl은_썸네일key가_있으면_presigned_GET_URL이다() {
			String thumbKey = "videos/thumb/1042.jpg";
			String signed = "https://s3.example/thumb.jpg?X-Amz-Signature=abc";
			given(userGridRepository.getCollectionGrids(1L, null, "COLLECTED", 30))
				.willReturn(List.of(gridProjection("19422_9582", 1042L, thumbKey)));
			given(thumbnailUrlPresigner.presign(thumbKey)).willReturn(signed);

			CollectionGridView view = collectDefault().get(0);

			assertThat(view.coverVideoId()).isEqualTo(1042L);
			assertThat(view.coverThumbnailUrl()).isEqualTo(signed);
		}

		// 검증: FR-COLLECT-08
		@Test
		@DisplayName("coverThumbnailUrl은 cover가 null이면 null이다")
		void coverThumbnailUrl은_cover가_null이면_null이다() {
			given(userGridRepository.getCollectionGrids(1L, null, "COLLECTED", 30))
				.willReturn(List.of(gridProjection("19422_9582", null, null)));

			CollectionGridView view = collectDefault().get(0);

			assertThat(view.coverVideoId()).isNull();
			assertThat(view.coverThumbnailUrl()).isNull();
		}

		// 검증: FR-MAP-10
		@Test
		@DisplayName("coverDurationSec은 프로젝션 값을 그대로 통과시킨다")
		void coverDurationSec은_프로젝션_값을_그대로_통과시킨다() {
			given(userGridRepository.getCollectionGrids(1L, null, "COLLECTED", 30))
				.willReturn(List.of(gridProjection("19422_9582", 1042L, null)));

			assertThat(collectDefault().get(0).coverDurationSec()).isEqualTo(12);
		}

		// 검증: FR-MAP-10
		@Test
		@DisplayName("regionCode 없이 limit 생략이면 30을 바인딩한다")
		void regionCode_없이_limit_생략이면_30을_바인딩한다() {
			collectDefault();

			then(userGridRepository).should().getCollectionGrids(1L, null, "COLLECTED", 30);
		}

		// 검증: FR-MAP-10
		@Test
		@DisplayName("regionCode 지정에 limit 생략이면 20을 바인딩한다")
		void regionCode_지정에_limit_생략이면_20을_바인딩한다() {
			userGridQueryService.getCollectionGrids(1L, "1168051500", CollectionGridSort.COLLECTED, null);

			then(userGridRepository).should().getCollectionGrids(1L, "1168051500", "COLLECTED", 20);
		}

		// 검증: FR-MAP-10
		@Test
		@DisplayName("limit 1 미만은 1로 보정한다")
		void limit_1_미만은_1로_보정한다() {
			userGridQueryService.getCollectionGrids(1L, null, CollectionGridSort.COLLECTED, 0);

			then(userGridRepository).should().getCollectionGrids(1L, null, "COLLECTED", 1);
		}

		// 검증: FR-MAP-10
		@Test
		@DisplayName("sort는 name 문자열로 리포지토리에 넘긴다")
		void sort는_name_문자열로_리포지토리에_넘긴다() {
			// enum 을 네이티브 쿼리에 그대로 넘기면 Hibernate 6 가 ordinal 로 바인딩해 텍스트 비교가 항상 실패한다.
			userGridQueryService.getCollectionGrids(1L, null, CollectionGridSort.UPLOADED, 20);

			then(userGridRepository).should().getCollectionGrids(1L, null, "UPLOADED", 20);
		}
	}

	@Nested
	@DisplayName("getCollectionGridPage")
	class GetCollectionGridPage {

		@Test
		@DisplayName("21개를 읽으면 20개와 마지막 반환행 커서를 만든다")
		void 이십일_개를_읽으면_이십_개와_마지막_반환행_커서를_만든다() {
			givenSeomyeonZone();
			List<CollectionGridProjection> rows = IntStream.range(0, 21)
				.mapToObj(i -> gridProjection("19422_" + (9582 - i), BASE_TIME.minusMinutes(i), 30 - i))
				.toList();
			given(userGridRepository.getCollectionGridPage(1L, "1168051500", null, null, null, 21))
				.willReturn(rows);

			CollectionGridPage page = userGridQueryService.getCollectionGridPage(1L, "1168051500", null);

			assertThat(page.items()).hasSize(20);
			assertThat(page.hasNext()).isTrue();
			CollectionGridProjection last = rows.get(19);
			assertThat(CollectionGridCursor.decode(page.nextCursor())).isEqualTo(new CollectionGridCursor(
				"1168051500", last.getLastUploadedAt(), last.getVideoCount(), last.getGridId()));
			then(thumbnailUrlPresigner).should(times(20)).presign(null);
		}

		@Test
		@DisplayName("20개 이하면 다음 페이지가 없다")
		void 이십_개_이하면_다음_페이지가_없다() {
			givenSeomyeonZone();
			given(userGridRepository.getCollectionGridPage(1L, "1168051500", null, null, null, 21))
				.willReturn(List.of(gridProjection("19422_9582", BASE_TIME, 1)));

			CollectionGridPage page = userGridQueryService.getCollectionGridPage(1L, "1168051500", null);

			assertThat(page.items()).hasSize(1);
			assertThat(page.hasNext()).isFalse();
			assertThat(page.nextCursor()).isNull();
		}

		@Test
		@DisplayName("커서 정렬값을 리포지토리에 전달한다")
		void 커서_정렬값을_리포지토리에_전달한다() {
			givenSeomyeonZone();
			LocalDateTime uploadedAt = BASE_TIME.minusMinutes(3);
			String cursor = CollectionGridCursor.encode("1168051500", uploadedAt, 7, "19422_9582");
			given(userGridRepository.getCollectionGridPage(
				1L, "1168051500", uploadedAt, 7, "19422_9582", 21)).willReturn(List.of());

			userGridQueryService.getCollectionGridPage(1L, "1168051500", cursor);

			then(userGridRepository).should().getCollectionGridPage(
				1L, "1168051500", uploadedAt, 7, "19422_9582", 21);
		}

		@Test
		@DisplayName("다른 행정동 커서와 깨진 커서는 BAD_REQUEST다")
		void 다른_행정동_커서와_깨진_커서는_BAD_REQUEST다() {
			String otherRegionCursor = CollectionGridCursor.encode("1111010100", BASE_TIME, 1, "19422_9582");

			assertThatThrownBy(() -> userGridQueryService.getCollectionGridPage(
				1L, "1168051500", otherRegionCursor))
				.isInstanceOfSatisfying(ApiException.class,
					exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
			assertThatThrownBy(() -> userGridQueryService.getCollectionGridPage(1L, "1168051500", "broken"))
				.isInstanceOfSatisfying(ApiException.class,
					exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
		}
	}

	@Nested
	@DisplayName("getRegionVideos")
	class GetRegionVideos {

		@BeforeEach
		void givenZone() {
			givenSeomyeonZone();
		}

		// 검증: FR-COLLECT-10
		@Test
		@DisplayName("thumbnailKey가 있으면 presigned GET URL로 바꾸고 gridId를 그대로 통과시킨다")
		void thumbnailKey가_있으면_presigned_GET_URL로_바꾸고_gridId를_그대로_통과시킨다() {
			String thumbKey = "videos/thumb/1042.jpg";
			String signed = "https://s3.example/thumb.jpg?X-Amz-Signature=abc";
			given(userGridRepository.getRegionVideos(1L, "1168051500"))
				.willReturn(List.of(regionVideoProjection(1042L, "19422_9582", thumbKey, "READY")));
			given(thumbnailUrlPresigner.presign(thumbKey)).willReturn(signed);

			RegionVideoView view = userGridQueryService.getRegionVideos(1L, "1168051500").get(0);

			assertThat(view.videoId()).isEqualTo(1042L);
			assertThat(view.gridId()).isEqualTo("19422_9582");
			assertThat(view.thumbnailUrl()).isEqualTo(signed);
			assertThat(view.durationSec()).isEqualTo(12);
		}

		// 검증: FR-COLLECT-10
		@Test
		@DisplayName("thumbnailKey가 null이면 thumbnailUrl이 null이다")
		void thumbnailKey가_null이면_thumbnailUrl이_null이다() {
			given(userGridRepository.getRegionVideos(1L, "1168051500"))
				.willReturn(List.of(regionVideoProjection(1041L, "19422_9582", null, "ENCODING")));

			RegionVideoView view = userGridQueryService.getRegionVideos(1L, "1168051500").get(0);

			assertThat(view.processingStatus()).isEqualTo("ENCODING");
			assertThat(view.thumbnailUrl()).isNull();
		}
	}

	@Nested
	@DisplayName("구역 이름 통과 (MSG-341)")
	class ZoneName {

		@Test
		@DisplayName("도감 목록과 지역별 갤러리와 친구 최근 수집 격자에 구역 이름이 붙는다")
		void 도감_목록과_지역별_갤러리와_친구_최근_수집_격자에_구역_이름이_붙는다() {
			// 뷰 3종이 같은 산식으로 이름을 받는지 한 자리에서 고정한다. 지역별 갤러리는 뷰에 gridY/gridX 가
			// 없어 이 매핑에서 gridId 를 새로 decode 하는 유일한 경로다 (D-5).
			givenSeomyeonZone();
			given(userGridRepository.getCollectionGrids(1L, null, "COLLECTED", 30))
				.willReturn(List.of(gridProjection("19422_9582", null, null)));
			given(userGridRepository.getRegionVideos(1L, "1168051500"))
				.willReturn(List.of(regionVideoProjection(1042L, "19422_9582", null, "ENCODING")));
			given(userGridRepository.getCollectionGridsForFriend(7L))
				.willReturn(List.of(friendGridProjection("19422_9582")));

			CollectionGridView collection = collectDefault().get(0);
			RegionVideoView regionVideo = userGridQueryService.getRegionVideos(1L, "1168051500").get(0);
			FriendCollectionGridView friendGrid = userGridQueryService.getCollectionGridsForFriend(7L).get(0);

			assertThat(collection.zoneName()).isEqualTo("서면");
			assertThat(collection.zoneCell()).isEqualTo("I-9");
			assertThat(regionVideo.zoneName()).isEqualTo("서면");
			assertThat(regionVideo.zoneCell()).isEqualTo("I-9");
			assertThat(friendGrid.zoneName()).isEqualTo("서면");
			assertThat(friendGrid.zoneCell()).isEqualTo("I-9");
		}

		@Test
		@DisplayName("구역 밖 격자는 두 필드가 모두 null이다")
		void 구역_밖_격자는_두_필드가_모두_null이다() {
			givenSeomyeonZone();
			given(userGridRepository.getCollectionGrids(1L, null, "COLLECTED", 30))
				.willReturn(List.of(gridProjection("16676_11596", null, null)));

			CollectionGridView view = collectDefault().get(0);

			assertThat(view.zoneName()).isNull();
			assertThat(view.zoneCell()).isNull();
			assertThat(view.regionName()).isEqualTo("역삼1동");   // 폴백 재료는 그대로 남는다
		}

		@Test
		@DisplayName("항목이 여러 건이어도 zones 조회는 1회다")
		void 항목이_여러_건이어도_zones_조회는_1회다() {
			// FR-8: 항목마다 리졸버를 받으면 격자 수만큼 zones 를 다시 읽는 N+1 이 된다.
			givenSeomyeonZone();
			given(userGridRepository.getCollectionGrids(1L, null, "COLLECTED", 30)).willReturn(List.of(
				gridProjection("19422_9582", null, null),
				gridProjection("19423_9583", null, null),
				gridProjection("19424_9584", null, null)));

			assertThat(collectDefault()).hasSize(3);

			then(zoneNameQueryService).should(times(1)).resolver();
		}
	}

	private FriendCollectionGridProjection friendGridProjection(String gridId) {
		return new FriendCollectionGridProjection() {
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
			public String getThumbnailKey() {
				return null;
			}

			@Override
			public String getRegionName() {
				return "역삼1동";
			}
		};
	}

	private RegionVideoProjection regionVideoProjection(
		Long videoId, String gridId, String thumbnailKey, String processingStatus) {
		return new RegionVideoProjection() {
			@Override
			public Long getVideoId() {
				return videoId;
			}

			@Override
			public String getGridId() {
				return gridId;
			}

			@Override
			public String getThumbnailKey() {
				return thumbnailKey;
			}

			@Override
			public String getProcessingStatus() {
				return processingStatus;
			}

			@Override
			public Integer getDurationSec() {
				return 12;
			}

			@Override
			public LocalDateTime getCreatedAt() {
				return LocalDateTime.of(2026, 7, 20, 18, 3, 11);
			}
		};
	}

	private CollectionGridProjection gridProjection(String gridId, Long coverVideoId, String coverThumbnailKey) {
		return gridProjection(gridId, LocalDateTime.of(2026, 7, 21, 9, 12, 0), 3,
			coverVideoId, coverThumbnailKey);
	}

	private CollectionGridProjection gridProjection(String gridId, LocalDateTime lastUploadedAt, int videoCount) {
		return gridProjection(gridId, lastUploadedAt, videoCount, null, null);
	}

	private CollectionGridProjection gridProjection(String gridId, LocalDateTime lastUploadedAt, int videoCount,
		Long coverVideoId, String coverThumbnailKey) {
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
				return lastUploadedAt;
			}

			@Override
			public Integer getVideoCount() {
				return videoCount;
			}

			@Override
			public Long getCoverVideoId() {
				return coverVideoId;
			}

			@Override
			public String getCoverThumbnailKey() {
				return coverThumbnailKey;
			}

			@Override
			public Integer getCoverDurationSec() {
				return 12;
			}

			@Override
			public String getRegionName() {
				return "역삼1동";
			}
		};
	}

	private CollectionSummaryProjection projection(int totalGridCount, long totalVideoCount, int visitedRegionCount,
		int currentStreak, int maxStreak, int badgeCount) {
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

			@Override
			public Integer getCurrentStreak() {
				return currentStreak;
			}

			@Override
			public Integer getMaxStreak() {
				return maxStreak;
			}

			@Override
			public Integer getBadgeCount() {
				return badgeCount;
			}
		};
	}

	private UploadHistoryProjection historyProjection(LocalDate uploadDate, int uploadCount) {
		return new UploadHistoryProjection() {
			@Override
			public LocalDate getUploadDate() {
				return uploadDate;
			}

			@Override
			public Integer getUploadCount() {
				return uploadCount;
			}
		};
	}
}
