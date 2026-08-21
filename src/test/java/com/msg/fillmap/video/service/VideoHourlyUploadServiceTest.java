package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.friend.service.FriendshipQueryService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.GridHourlyUploadResponseDto;
import com.msg.fillmap.video.dto.HourlyUploadCountResponseDto;
import com.msg.fillmap.video.repository.HourlyUploadProjection;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

/**
 * 격자 전역 시간대 분포의 서비스 조립 (MSG-372). 게이트·KST 변환은 repository 계약이라
 * VideoHourlyUploadQueryTest 가 실 DB 로 검증하고, 여기서는 24구간 채움·저장 존 바인딩만 본다
 * (VideoGlobalListServiceTest 의 역할 분담 전략).
 */
@DisplayName("VideoService 격자 전역 시간대 분포 조회")
class VideoHourlyUploadServiceTest {

	private static final String GRID_ID = "19422_9582";

	private VideoRepository videoRepository;
	private VideoService videoService;

	@BeforeEach
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));

		videoService = new VideoServiceImpl(
			videoRepository, mock(VideoEncodingService.class), mock(VideoStatusWriter.class),
			mock(S3Presigner.class), mock(S3Client.class), properties,
			mock(RegionStatsCommandService.class), mock(ThumbnailUrlPresigner.class),
			mock(BadgeAwardService.class), mock(StreakCommandService.class), mock(MissionAwardService.class),
			mock(HotScoreCommandService.class), mock(FriendshipQueryService.class),
			mock(ZoneNameQueryService.class), mock(VideoProcessingMetrics.class), mock(EventVideoRepository.class));
	}

	/** 저장 존 인자가 어긋나면 이 스텁이 매치되지 않아 테스트가 깨진다 — UTC 배선 검증을 겸한다. */
	private void givenRows(HourlyUploadProjection... rows) {
		given(videoRepository.countHourlyUploadsByGrid(GRID_ID, ZoneOffset.UTC.getId())).willReturn(List.of(rows));
	}

	private static HourlyUploadProjection row(int hour, long count) {
		HourlyUploadProjection projection = mock(HourlyUploadProjection.class);
		given(projection.getHour()).willReturn(hour);
		given(projection.getCount()).willReturn(count);
		return projection;
	}

	private static long countAt(GridHourlyUploadResponseDto response, int hour) {
		return response.hours().get(hour).count();
	}

	@Test
	// 검증: FR-MAP-09
	@DisplayName("응답은 항상 0시부터 23시까지 24구간이고 빈 구간은 0이다")
	void 응답은_항상_0시부터_23시까지_24구간이고_빈_구간은_0이다() {
		givenRows(row(10, 2L), row(18, 5L));

		GridHourlyUploadResponseDto response = videoService.getGridHourlyUploads(GRID_ID);

		assertThat(response.gridId()).isEqualTo(GRID_ID);
		assertThat(response.hours()).hasSize(24)
			.extracting(HourlyUploadCountResponseDto::hour)
			.containsExactlyElementsOf(IntStream.range(0, 24).boxed().toList());
		assertThat(countAt(response, 10)).isEqualTo(2L);
		assertThat(countAt(response, 18)).isEqualTo(5L);
		assertThat(response.hours()).filteredOn(slot -> slot.count() > 0).hasSize(2);
	}

	@Test
	@DisplayName("공개 영상이 없는 격자는 전 구간 0인 정상 응답이다")
	void 공개_영상이_없는_격자는_전_구간_0인_정상_응답이다() {
		givenRows();

		GridHourlyUploadResponseDto response = videoService.getGridHourlyUploads(GRID_ID);

		assertThat(response.hours()).hasSize(24).allMatch(slot -> slot.count() == 0L);
	}

	@Test
	@DisplayName("존재하지 않는 gridId 도 전 구간 0인 정상 응답이다")
	void 존재하지_않는_gridId도_전_구간_0인_정상_응답이다() {
		given(videoRepository.countHourlyUploadsByGrid("0_0", ZoneOffset.UTC.getId())).willReturn(List.of());

		GridHourlyUploadResponseDto response = videoService.getGridHourlyUploads("0_0");

		assertThat(response.gridId()).isEqualTo("0_0");
		assertThat(response.hours()).hasSize(24).allMatch(slot -> slot.count() == 0L);
	}

	@Test
	@DisplayName("경계 시간대 0시와 23시 구간도 그대로 실린다")
	void 경계_시간대_0시와_23시_구간도_그대로_실린다() {
		givenRows(row(0, 1L), row(23, 7L));

		GridHourlyUploadResponseDto response = videoService.getGridHourlyUploads(GRID_ID);

		assertThat(countAt(response, 0)).isEqualTo(1L);
		assertThat(countAt(response, 23)).isEqualTo(7L);
	}
}
