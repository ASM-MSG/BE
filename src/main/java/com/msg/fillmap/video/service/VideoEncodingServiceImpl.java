package com.msg.fillmap.video.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.config.AsyncConfig;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.FfmpegRunner;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoEncodingServiceImpl implements VideoEncodingService {

	// 스키마 CHECK(duration_sec <= 30) 와 같은 상한 — 실제 영상 길이로 재확인한다 (MSG-65 D7).
	private static final double MAX_DURATION_SEC = 30.0;

	private final VideoRepository videoRepository;
	private final VideoStatusWriter statusWriter;
	private final FfmpegRunner ffmpegRunner;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;

	@Override
	@Async(AsyncConfig.ENCODING_EXECUTOR)
	public void encode(Long videoId) {
		Video video = videoRepository.findById(videoId).orElse(null);
		if (video == null) {
			log.error("인코딩 대상 영상 없음: videoId={}", videoId);
			return;
		}

		Path workDir = null;
		try {
			statusWriter.markEncoding(videoId);
			workDir = Files.createTempDirectory("encode-" + videoId + "-");

			Path original = workDir.resolve("original");
			download(video.getOriginalS3Key(), original);

			double duration = ffmpegRunner.probeDurationSec(original);
			if (duration > MAX_DURATION_SEC) {
				log.warn("영상 길이 초과로 인코딩 중단: videoId={} duration={}s", videoId, duration);
				statusWriter.markFailed(videoId);
				return;
			}

			Path encoded = workDir.resolve("encoded.mp4");
			Path thumbnail = workDir.resolve("thumb.jpg");
			ffmpegRunner.encode720p(original, encoded);
			ffmpegRunner.extractThumbnail(original, thumbnail, duration);

			String encodedKey = "videos/encoded/%d/%d.mp4".formatted(video.getUserId(), videoId);
			String thumbnailKey = "videos/thumb/%d/%d.jpg".formatted(video.getUserId(), videoId);
			upload(encodedKey, "video/mp4", encoded);
			upload(thumbnailKey, "image/jpeg", thumbnail);

			statusWriter.markReady(videoId, encodedKey, thumbnailKey);
			log.info("인코딩 완료: videoId={} duration={}s", videoId, duration);
		} catch (Exception e) {
			// 비동기라 던져봐야 받을 곳이 없다. 기록만 남기고 재시도하지 않는다 (MSG-65 D8).
			log.error("인코딩 실패: videoId={}", videoId, e);
			statusWriter.markFailed(videoId);
		} finally {
			deleteQuietly(workDir);
		}
	}

	private void download(String s3Key, Path target) {
		s3Client.getObject(
			GetObjectRequest.builder().bucket(awsProperties.s3().bucket()).key(s3Key).build(),
			target);
	}

	private void upload(String s3Key, String contentType, Path source) {
		s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.key(s3Key)
				.contentType(contentType)
				.build(),
			RequestBody.fromFile(source));
	}

	/** 임시파일이 쌓이면 t3.small 디스크가 먼저 죽는다. 삭제 실패는 로그만 남기고 넘어간다. */
	private void deleteQuietly(Path dir) {
		if (dir == null) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					log.warn("임시파일 삭제 실패: {}", p, e);
				}
			});
		} catch (IOException e) {
			log.warn("임시 디렉터리 정리 실패: {}", dir, e);
		}
	}
}
