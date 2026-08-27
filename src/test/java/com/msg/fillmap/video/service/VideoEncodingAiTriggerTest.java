package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.config.AiProperties;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.FfmpegRunner;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.VideoAssetKeys;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * MSG-149/456 트리거: 인코딩 완료 지점의 실효 블러 활성(enabled && blurEnabled) 분기와, 블러 꺼짐 경로의
 * 후행 하이라이트 워커(D-1)를 검증한다. ffmpeg·S3 는 목이라 CI 에 ffmpeg 가 없어도 돈다
 * (VideoEncodingServiceTest 관례). highlightExecutor 목은 execute 를 가로채 Runnable 을 즉시 실행시켜
 * 워커 본문까지 단위 테스트에서 동기로 검증한다.
 */
@DisplayName("인코딩 완료 → AI 트리거 분기")
class VideoEncodingAiTriggerTest {

	private static final long VIDEO_ID = 7L;
	private static final String ORIGINAL_KEY = "videos/original/1/x.mp4";
	private static final VideoAssetKeys ASSET_KEYS = VideoAssetKeys.from(1L, VIDEO_ID, ORIGINAL_KEY);
	private static final String ENCODED_KEY = ASSET_KEYS.encoded();
	private static final String THUMBNAIL_KEY = ASSET_KEYS.thumbnail();
	private static final List<List<Double>> 하이라이트_구간 = List.of(List.of(0.0, 3.33));

	private VideoRepository videoRepository;
	private VideoStatusWriter statusWriter;
	private FfmpegRunner ffmpegRunner;
	private S3Client s3Client;
	private VideoProcessingMetrics videoProcessingMetrics;
	private AiClient aiClient;
	private ObjectProvider<AiClient> aiClientProvider;
	private ThreadPoolTaskExecutor highlightExecutor;
	private EncodingJobClaim claim;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		statusWriter = mock(VideoStatusWriter.class);
		ffmpegRunner = mock(FfmpegRunner.class);
		s3Client = mock(S3Client.class);
		videoProcessingMetrics = mock(VideoProcessingMetrics.class);
		aiClient = mock(AiClient.class);
		aiClientProvider = mock(ObjectProvider.class);
		highlightExecutor = mock(ThreadPoolTaskExecutor.class);
		// 목 실행기 — execute 를 가로채 즉시 실행해 워커 본문을 동기로 태운다 (구체 클래스라 mockito 목 가능)
		willAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).given(highlightExecutor).execute(any(Runnable.class));

		Video video = Video.create(1L, "19495_9607", ORIGINAL_KEY,
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now(), Visibility.PRIVATE);
		claim = new EncodingJobClaim(1L, VIDEO_ID, ORIGINAL_KEY, UUID.randomUUID(),
			(short) 1, LocalDateTime.of(2026, 8, 27, 0, 0));
		given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(video));
		given(statusWriter.markEncoding(claim)).willReturn(true);
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());
	}

	/** 플래그 조합별 서비스 조립 — AiProperties 는 record 직접 생성 (MSG-456 스펙 "기존 테스트 파급"). */
	private VideoEncodingServiceImpl service(boolean enabled, boolean blurEnabled) {
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));
		return new VideoEncodingServiceImpl(
			videoRepository, statusWriter, ffmpegRunner, s3Client, properties, videoProcessingMetrics,
			new AiProperties(enabled, blurEnabled, "http://ai.test", Duration.ofMinutes(30), 30000L),
			aiClientProvider, highlightExecutor);
	}

	// 검증: FR-MEDIA-05, FR-MEDIA-02
	@Test
	void 블러가_켜져_있으면_인코딩_완료가_BLURRING으로_전이한다() {
		service(true, true).encode(claim);

		// thumbnail 은 폴러가 완료 시 기록(R5)
		verify(statusWriter).markEncoded(claim, ENCODED_KEY, (short) 10);
		verify(statusWriter, never()).markReady(eq(claim), any(), any(), anyShort());
		verify(highlightExecutor, never()).execute(any(Runnable.class));
	}

	// 검증: FR-MEDIA-18
	@Test
	void 블러가_꺼져_있으면_READY_전이_후_워커가_하이라이트를_저장한다() {
		given(aiClientProvider.getIfAvailable()).willReturn(aiClient);
		given(aiClientProvider.getObject()).willReturn(aiClient);
		given(aiClient.analyzeHighlights(any(Path.class))).willReturn(하이라이트_구간);

		service(true, false).encode(claim);

		// D-1 핵심: READY 전이가 워커 제출보다 먼저다
		InOrder readyFirst = inOrder(statusWriter, highlightExecutor);
		readyFirst.verify(statusWriter).markReady(claim, ENCODED_KEY, THUMBNAIL_KEY, (short) 10);
		readyFirst.verify(highlightExecutor).execute(any(Runnable.class));
		// 워커 입력은 tmp 가 아니라 S3 인코딩본 — 원본(1번째)에 이어 2번째 다운로드가 encoded 키 대상이다
		ArgumentCaptor<GetObjectRequest> downloads = ArgumentCaptor.forClass(GetObjectRequest.class);
		verify(s3Client, times(2)).getObject(downloads.capture(), any(Path.class));
		assertThat(downloads.getAllValues().get(1).key()).isEqualTo(ENCODED_KEY);
		verify(statusWriter).recordHighlights(VIDEO_ID, ORIGINAL_KEY, 하이라이트_구간);
		verify(statusWriter, never()).markEncoded(eq(claim), any(), anyShort());
	}

	// 검증: FR-MEDIA-18
	@Test
	void 하이라이트_계산이_실패해도_이미_끝난_READY와_완료_계측은_그대로다() {
		given(aiClientProvider.getIfAvailable()).willReturn(aiClient);
		given(aiClientProvider.getObject()).willReturn(aiClient);
		given(aiClient.analyzeHighlights(any(Path.class)))
			.willThrow(new AiClient.HighlightUpstreamException("AI 서버 다운"));

		service(true, false).encode(claim);

		verify(statusWriter).markReady(claim, ENCODED_KEY, THUMBNAIL_KEY, (short) 10);
		verify(statusWriter, never()).recordHighlights(anyLong(), any(), any());
		verify(statusWriter, never()).markFailed(any(EncodingJobClaim.class));
		// 계측 선행 확인 — 워커 본문의 실패는 인코딩 태스크 계측과 무관하다
		verify(videoProcessingMetrics).countEncodingTask(VideoProcessingMetrics.TASK_COMPLETED);
		verify(videoProcessingMetrics, never()).countEncodingTask(VideoProcessingMetrics.TASK_FAILED_ERROR);
	}

	// 검증: FR-MEDIA-18
	@Test
	void 하이라이트_큐가_포화면_폐기되고_READY는_이미_끝나_있다() {
		given(aiClientProvider.getIfAvailable()).willReturn(aiClient);
		willThrow(new RejectedExecutionException("큐 포화"))
			.given(highlightExecutor).execute(any(Runnable.class));

		service(true, false).encode(claim);

		// 거부가 인코딩 경로로 새지 않는다 — 그 영상만 하이라이트 null 로 남는다
		verify(statusWriter).markReady(claim, ENCODED_KEY, THUMBNAIL_KEY, (short) 10);
		verify(statusWriter, never()).recordHighlights(anyLong(), any(), any());
		verify(statusWriter, never()).markFailed(any(EncodingJobClaim.class));
	}

	// 검증: FR-MEDIA-05
	@Test
	void AI가_꺼져_있으면_하이라이트_계산_없이_READY로_끝난다() {
		// ObjectProvider 는 빈 값 — getIfAvailable() 이 null (목 기본 동작). ai.enabled=false 는 지금과 동일 (FR-5)
		service(false, false).encode(claim);

		verify(statusWriter).markReady(claim, ENCODED_KEY, THUMBNAIL_KEY, (short) 10);
		verify(highlightExecutor, never()).execute(any(Runnable.class));
		verify(statusWriter, never()).recordHighlights(anyLong(), any(), any());
	}

	/** 목 호출 시 출력 경로(두 번째 인자)에 빈 파일을 만들어 준다 (VideoEncodingServiceTest 관례). */
	private FfmpegRunner createFileOn(FfmpegRunner mock) {
		return org.mockito.BDDMockito.willAnswer(invocation -> {
			Path output = invocation.getArgument(1);
			Files.createDirectories(output.getParent());
			Files.write(output, new byte[] {1});
			return null;
		}).given(mock);
	}
}
