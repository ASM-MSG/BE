package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.FfmpegRunner;
import com.msg.fillmap.video.support.GeoSupport;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * ffmpeg·S3 를 목으로 대체해 파이프라인 분기만 검증한다 (CI 에 ffmpeg 가 없어도 돈다).
 * 실제 인코딩 산출물 검증은 FfmpegRunnerTest 가 담당한다.
 */
@DisplayName("VideoEncodingService 파이프라인 분기")
class VideoEncodingServiceTest {

	private static final long VIDEO_ID = 7L;
	private static final String ORIGINAL_KEY = "videos/original/1/x.mp4";

	private VideoRepository videoRepository;
	private VideoStatusWriter statusWriter;
	private FfmpegRunner ffmpegRunner;
	private S3Client s3Client;
	private VideoEncodingService encodingService;
	private Video video;

	@BeforeEach
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		statusWriter = mock(VideoStatusWriter.class);
		ffmpegRunner = mock(FfmpegRunner.class);
		s3Client = mock(S3Client.class);
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L));

		encodingService = new VideoEncodingServiceImpl(
			videoRepository, statusWriter, ffmpegRunner, s3Client, properties);

		video = Video.create(1L, "41716_110483", ORIGINAL_KEY,
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now(), Visibility.PRIVATE);
		given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(video));
		given(statusWriter.markEncoding(VIDEO_ID, ORIGINAL_KEY)).willReturn(true);
	}

	@Test
	void 정상_영상이면_ENCODING_거쳐_READY_로_전이한다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		// 업로드는 실제 파일을 읽으므로, 목 ffmpeg 가 산출물을 만든 것처럼 흉내낸다.
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);

		verify(statusWriter).markEncoding(VIDEO_ID, ORIGINAL_KEY);
		verify(statusWriter).markReady(VIDEO_ID, ORIGINAL_KEY, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");
		verify(statusWriter, never()).markFailed(VIDEO_ID, ORIGINAL_KEY);
	}

	/** 목 호출 시 출력 경로(마지막에서 두 번째 인자가 아닌 Path 인자)에 빈 파일을 만들어 준다. */
	private FfmpegRunner createFileOn(FfmpegRunner mock) {
		return org.mockito.BDDMockito.willAnswer(invocation -> {
			Path output = invocation.getArgument(1);
			Files.createDirectories(output.getParent());
			Files.write(output, new byte[] {1});
			return null;
		}).given(mock);
	}

	@Test
	void 길이가_30초를_넘으면_인코딩하지_않고_FAILED_다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(30.5);

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);

		verify(statusWriter).markFailed(VIDEO_ID, ORIGINAL_KEY);
		verify(ffmpegRunner, never()).encode720p(any(), any());
		verify(statusWriter, never()).markReady(eq(VIDEO_ID), any(), any(), any());
	}

	@Test
	void 손상_영상이라_ffprobe_가_실패하면_FAILED_다() {
		willThrow(new IllegalStateException("ffprobe 실패")).given(ffmpegRunner).probeDurationSec(any());

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);

		verify(statusWriter).markFailed(VIDEO_ID, ORIGINAL_KEY);
		verify(statusWriter, never()).markReady(eq(VIDEO_ID), any(), any(), any());
	}

	@Test
	void 인코딩_도중_실패해도_예외를_밖으로_던지지_않고_FAILED_로_기록한다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		willThrow(new IllegalStateException("ffmpeg 실패"))
			.given(ffmpegRunner).encode720p(any(Path.class), any(Path.class));

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);   // 예외가 새어나오면 테스트 실패

		verify(statusWriter).markFailed(VIDEO_ID, ORIGINAL_KEY);
	}

	@Test
	void 대상_영상이_없으면_아무_전이도_하지_않는다() {
		given(videoRepository.findById(999L)).willReturn(Optional.empty());

		encodingService.encode(999L, ORIGINAL_KEY);

		verify(statusWriter, never()).markEncoding(999L, ORIGINAL_KEY);
		verify(statusWriter, never()).markFailed(999L, ORIGINAL_KEY);
	}

	@Test
	void 썸네일_추출은_실제_길이를_받아_seek_지점을_고른다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(0.5);

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);

		verify(ffmpegRunner).extractThumbnail(any(), any(), eq(0.5));
	}

	@Test
	void S3_다운로드가_실패하면_FAILED_다() {
		willThrow(new RuntimeException("S3 다운로드 실패"))
			.given(s3Client).getObject(any(GetObjectRequest.class), any(Path.class));

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);

		verify(statusWriter).markFailed(VIDEO_ID, ORIGINAL_KEY);
		verify(ffmpegRunner, never()).probeDurationSec(any());
	}

	@Test
	void AI_활성이면_인코딩_단계에서_미블러_썸네일을_올리지_않는다() {
		ReflectionTestUtils.setField(encodingService, "aiEnabled", true);
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		createFileOn(ffmpegRunner).encode720p(any(), any());

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);

		// 인코딩본 하나만 올린다 — 미블러 썸네일은 추출도 업로드도 하지 않는다 (P1).
		ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3Client, times(1)).putObject(captor.capture(), any(RequestBody.class));
		assertThat(captor.getValue().key()).isEqualTo("videos/encoded/1/7.mp4");
		verify(ffmpegRunner, never()).extractThumbnail(any(), any(), anyDouble());
		// thumbnail 키는 폴러가 완료 시 기록(R5)
		verify(statusWriter).markEncoded(VIDEO_ID, ORIGINAL_KEY, "videos/encoded/1/7.mp4");
	}

	@Test
	void 교체로_원본이_바뀌면_인코딩_결과를_업로드하지_않는다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());
		// ffmpeg 가 도는 사이 사용자가 교체 — 업로드 직전 fresh 로드가 다른 원본 키를 돌려준다 (MSG-241).
		video.replaceFile("videos/original/1/y.mp4", (short) 8, LocalDateTime.now());

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);

		verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
		verify(statusWriter, never()).markReady(eq(VIDEO_ID), any(), any(), any());
	}
}
