package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
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
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameResolver;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 영상 처리 종결 계측 (MSG-343 모듈 1). Counter 는 전이 적용 분기에서만 1회, Timer 는 인메모리
 * 시작점(D2)이 있을 때만 기록된다. 시각원은 AtomicLong 주입으로 고정해 결정적으로 단언한다.
 */
@DisplayName("VideoProcessingMetrics — 영상 처리 종결 계측")
class VideoProcessingMetricsTest {

	private static final long VIDEO_ID = 7L;
	private static final String K1 = "videos/original/1/x.mp4";
	private static final String K2 = "videos/original/1/y.mp4";

	private SimpleMeterRegistry registry;
	private AtomicLong nano;
	private VideoProcessingMetrics metrics;
	private VideoRepository videoRepository;
	private VideoStatusWriter statusWriter;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		nano = new AtomicLong();
		metrics = new VideoProcessingMetrics(registry, nano::get);
		videoRepository = mock(VideoRepository.class);
		UserRepository userRepository = mock(UserRepository.class);
		given(videoRepository.findUserIdById(VIDEO_ID)).willReturn(Optional.of(1L));
		given(userRepository.findIdForKeyShare(1L)).willReturn(Optional.of(1L));
		statusWriter = new VideoStatusWriter(videoRepository, userRepository,
			mock(NotificationCommandService.class), metrics);
	}

	/** K1 원본으로 ENCODING 중인 시도의 영상 (VideoStatusWriterTest 픽스처 관례). */
	private Video encoding() {
		Video video = Video.create(1L, "19495_9607", K1,
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now(ZoneOffset.UTC), Visibility.PRIVATE);
		video.markEncoding();
		ReflectionTestUtils.setField(video, "id", VIDEO_ID);
		return video;
	}

	/** AI 시도까지 가서 job_id 가 붙은 BLURRING 영상. */
	private Video blurring(String jobId) {
		Video video = encoding();
		video.markEncoded("videos/encoded/1/7.mp4");
		video.recordAiJob(jobId);
		return video;
	}

	private double outcomeCount(String outcome, String path) {
		return registry.get("video.processing.outcome").tags("outcome", outcome, "path", path).counter().count();
	}

	private io.micrometer.core.instrument.Timer durationTimer(String outcome, String path) {
		return registry.get("video.processing.duration").tags("outcome", outcome, "path", path).timer();
	}

	@Test
	void 시작점_등록_후_READY_전이는_카운터와_타이머를_각_한_번_기록한다() {
		Video video = encoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));
		nano.set(0L);
		metrics.markStart(VIDEO_ID);
		nano.set(TimeUnit.SECONDS.toNanos(5));

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");

		assertThat(outcomeCount("ready", "encoding")).isEqualTo(1.0);
		assertThat(durationTimer("ready", "encoding").count()).isEqualTo(1L);
		assertThat(durationTimer("ready", "encoding").totalTime(TimeUnit.SECONDS)).isEqualTo(5.0);
	}

	@Test
	void FAILED_전이는_outcome_failed로_기록된다() {
		// encoding 경로 — markFailed
		Video encodingVideo = encoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(encodingVideo));
		statusWriter.markFailed(VIDEO_ID, K1);
		assertThat(outcomeCount("failed", "encoding")).isEqualTo(1.0);

		// ai 경로 — markBlurFailed
		Video blurringVideo = blurring("job-1");
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(blurringVideo));
		statusWriter.markBlurFailed(VIDEO_ID, "job-1", blurringVideo.getBlurringStartedAt(), null);
		assertThat(outcomeCount("failed", "ai")).isEqualTo(1.0);

		assertThat(outcomeCount("ready", "encoding")).isZero();
		assertThat(outcomeCount("ready", "ai")).isZero();
	}

	@Test
	void 시작점_없이_종결되면_카운터만_증가하고_타이머는_기록되지_않는다() {
		// 재시작 유실 시나리오 (D2) — 인메모리 시작점이 없어도 Counter 는 정확히 증가한다.
		Video video = encoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");

		assertThat(outcomeCount("ready", "encoding")).isEqualTo(1.0);
		assertThat(durationTimer("ready", "encoding").count()).isZero();
	}

	@Test
	void 스테일_전이_skip은_어떤_Metric도_증가시키지_않는다() {
		// 큐 대기 중 교체 — 옛 시도의 종결은 가드가 skip 하고 계측도 함께 skip 된다 (D5).
		Video video = encoding();
		video.replaceFile(K2, (short) 8, LocalDateTime.now(ZoneOffset.UTC));
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));
		metrics.markStart(VIDEO_ID);

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");
		statusWriter.markFailed(VIDEO_ID, K1);

		assertThat(outcomeCount("ready", "encoding")).isZero();
		assertThat(outcomeCount("failed", "encoding")).isZero();
		assertThat(durationTimer("ready", "encoding").count()).isZero();
		assertThat(durationTimer("failed", "encoding").count()).isZero();
	}

	@Test
	void 교체_재등록이_시작점을_덮어써_옛_시도_시각이_쓰이지_않는다() {
		Video video = encoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));
		nano.set(0L);
		metrics.markStart(VIDEO_ID);                       // 원 업로드 시도
		nano.set(TimeUnit.SECONDS.toNanos(60));
		metrics.markStart(VIDEO_ID);                       // 교체 재등록 — 같은 키 덮어쓰기 (D2)
		nano.set(TimeUnit.SECONDS.toNanos(63));

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");

		assertThat(durationTimer("ready", "encoding").totalTime(TimeUnit.SECONDS)).isEqualTo(3.0);
	}

	@Test
	void 삭제_시_시작점이_제거된다() {
		Video video = encoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));
		metrics.markStart(VIDEO_ID);

		videoService().deleteVideo(1L, VIDEO_ID);

		// 시작점이 제거됐으면 이후 종결은 Timer 표본 없이 Counter 만 남는다.
		metrics.recordOutcome(VIDEO_ID, true, VideoProcessingMetrics.PATH_ENCODING);
		assertThat(durationTimer("ready", "encoding").count()).isZero();
	}

	@Test
	void 업로드_확정이_시작점을_등록한다() {
		// submitEncoding 진입부 등록 (도메인 로직 표) — 확정 커밋 직후, 업로드와 교체 공용 지점.
		Video saved = Video.create(1L, "19495_9607", K1, null, (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), Visibility.PRIVATE);
		ReflectionTestUtils.setField(saved, "id", VIDEO_ID);
		given(videoRepository.saveAndFlush(any(Video.class))).willReturn(saved);

		videoService().saveVideo(1L, new VideoUploadRequestDto(
			"videos/pending/1/x.mp4", 37.5445, 127.0560, (short) 10, LocalDateTime.now(ZoneOffset.UTC), "PRIVATE"));

		metrics.recordOutcome(VIDEO_ID, true, VideoProcessingMetrics.PATH_ENCODING);
		assertThat(durationTimer("ready", "encoding").count()).isEqualTo(1L);
	}

	/** 업로드·삭제 흐름 검증용 최소 목 조립 (VideoEncodingTriggerTest 레시피). */
	private VideoService videoService() {
		MissionAwardService missionAwardService = mock(MissionAwardService.class);
		given(missionAwardService.awardOnUpload(anyLong(), anyString())).willReturn(MissionAwardResult.EMPTY);
		S3Client s3Client = mock(S3Client.class);
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willReturn(DeleteObjectsResponse.builder().build());
		return new VideoServiceImpl(videoRepository, mock(VideoEncodingService.class), statusWriter,
			mock(S3Presigner.class), s3Client,
			new AwsProperties("ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L)),
			mock(RegionStatsCommandService.class), mock(ThumbnailUrlPresigner.class), mock(BadgeAwardService.class),
			mock(StreakCommandService.class), missionAwardService, mock(HotScoreCommandService.class),
			mock(FriendshipQueryService.class), () -> new ZoneNameResolver(List.of()), metrics,
			mock(EventVideoRepository.class));
	}
}
