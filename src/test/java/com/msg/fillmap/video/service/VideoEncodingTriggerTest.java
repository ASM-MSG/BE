package com.msg.fillmap.video.service;

import static com.msg.fillmap.video.support.S3VideoObjectStub.givenUploadedVideoObject;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.friend.service.FriendshipQueryService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoEncodingJobRepository;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameResolver;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@DisplayName("인코딩 작업 등록")
class VideoEncodingTriggerTest {

	private static final long USER_ID = 1L;

	@Test
	void 업로드를_확정하면_같은_시도의_DB작업을_등록한다() {
		VideoRepository videoRepository = mock(VideoRepository.class);
		VideoEncodingJobRepository jobRepository = mock(VideoEncodingJobRepository.class);
		Video saved = Video.create(USER_ID, "19495_9607", "videos/original/1/x.mp4", null, (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), Visibility.PRIVATE);
		ReflectionTestUtils.setField(saved, "id", 7L);
		given(videoRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(Video.class))).willReturn(saved);

		MissionAwardService missionAwardService = mock(MissionAwardService.class);
		given(missionAwardService.awardOnUpload(anyLong(), anyString())).willReturn(MissionAwardResult.EMPTY);
		S3Client s3Client = mock(S3Client.class);
		givenUploadedVideoObject(s3Client);
		VideoService service = new VideoServiceImpl(videoRepository, jobRepository,
			mock(S3Presigner.class), s3Client,
			new AwsProperties("ap-northeast-2",
				new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L)),
			mock(RegionStatsCommandService.class), mock(ThumbnailUrlPresigner.class),
			mock(BadgeAwardService.class), mock(StreakCommandService.class), missionAwardService,
			mock(HotScoreCommandService.class), mock(FriendshipQueryService.class),
			() -> new ZoneNameResolver(List.of()), mock(EventVideoRepository.class));

		service.saveVideo(USER_ID, new VideoUploadRequestDto(
			"videos/pending/1/x.mp4", 37.5445, 127.0560, (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), "PRIVATE"));

		verify(jobRepository).enqueue(
			org.mockito.ArgumentMatchers.eq(7L),
			org.mockito.ArgumentMatchers.startsWith("videos/original/1/x-"));
	}
}
