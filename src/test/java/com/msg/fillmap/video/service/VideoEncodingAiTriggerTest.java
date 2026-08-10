package com.msg.fillmap.video.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.FfmpegRunner;
import com.msg.fillmap.video.support.GeoSupport;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * MSG-149 트리거: 인코딩 완료 지점의 ai.enabled 분기만 검증한다.
 * ffmpeg·S3 는 목이라 CI 에 ffmpeg 가 없어도 돈다 (VideoEncodingServiceTest 관례).
 */
@DisplayName("인코딩 완료 → AI 트리거 분기")
class VideoEncodingAiTriggerTest {

	private static final long VIDEO_ID = 7L;
	private static final String ORIGINAL_KEY = "videos/original/1/x.mp4";

	private VideoRepository videoRepository;
	private VideoStatusWriter statusWriter;
	private FfmpegRunner ffmpegRunner;
	private VideoEncodingServiceImpl encodingService;

	@BeforeEach
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		statusWriter = mock(VideoStatusWriter.class);
		ffmpegRunner = mock(FfmpegRunner.class);
		S3Client s3Client = mock(S3Client.class);
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));

		encodingService = new VideoEncodingServiceImpl(
			videoRepository, statusWriter, ffmpegRunner, s3Client, properties);

		Video video = Video.create(1L, "19495_9607", ORIGINAL_KEY,
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now(), Visibility.PRIVATE);
		given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(video));
		given(statusWriter.markEncoding(VIDEO_ID, ORIGINAL_KEY)).willReturn(true);
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());
	}

	@Test
	void AI_활성이면_인코딩_완료_시_BLURRING으로_전이하고_encoded_키가_저장된다() {
		ReflectionTestUtils.setField(encodingService, "aiEnabled", true);

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);

		// thumbnail 은 폴러가 완료 시 기록(R5)
		verify(statusWriter).markEncoded(VIDEO_ID, ORIGINAL_KEY, "videos/encoded/1/7.mp4");
		verify(statusWriter, never()).markReady(eq(VIDEO_ID), any(), any(), any());
	}

	@Test
	void AI_비활성이면_인코딩_완료_시_READY로_끝난다() {
		ReflectionTestUtils.setField(encodingService, "aiEnabled", false);

		encodingService.encode(VIDEO_ID, ORIGINAL_KEY);

		verify(statusWriter).markReady(VIDEO_ID, ORIGINAL_KEY, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");
		verify(statusWriter, never()).markEncoded(eq(VIDEO_ID), any(), any());
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
