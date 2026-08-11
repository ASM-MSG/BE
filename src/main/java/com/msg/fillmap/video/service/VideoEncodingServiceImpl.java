package com.msg.fillmap.video.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
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
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.FfmpegRunner;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoEncodingServiceImpl implements VideoEncodingService {

	// 스키마 CHECK(duration_sec <= 30) 상한에 판정 여유 1초 — 업로드는 클라 신고 정수(≤30)로 통과하는데
	// 실측은 컨테이너 메타데이터 반올림으로 30.0x 초가 나와, 정확히 30.0 으로 끊으면 정상 영상이 FAILED 로
	// 끝난다 (MSG-370). 여유 구간(30~31초)의 초과분은 인코딩이 자르지 않고 그대로 둔다.
	private static final double MAX_DURATION_SEC = 31.0;

	private final VideoRepository videoRepository;
	private final VideoStatusWriter statusWriter;
	private final FfmpegRunner ffmpegRunner;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;

	// AI 활성이면 인코딩 완료가 READY 대신 BLURRING 으로 가서 폴러가 이어받는다 (MSG-149).
	// RegionSeeder 게이트 관례처럼 @Value 로 읽어 기본 off — 비활성이면 오늘 흐름(READY) 그대로다.
	@Value("${ai.enabled:false}")
	private boolean aiEnabled;

	@Override
	@Async(AsyncConfig.ENCODING_EXECUTOR)
	public void encode(Long videoId, String originalKey) {
		Video video = videoRepository.findById(videoId).orElse(null);
		if (video == null) {
			log.error("인코딩 대상 영상 없음: videoId={}", videoId);
			return;
		}

		Path workDir = null;
		try {
			// 큐 대기 중 교체/삭제됐으면 이 태스크의 원본은 더는 현재 시도가 아니다 — ffmpeg 을 돌리지 않는다 (MSG-241).
			if (!statusWriter.markEncoding(videoId, originalKey)) {
				return;
			}
			workDir = Files.createTempDirectory("encode-" + videoId + "-");

			Path original = workDir.resolve("original");
			// fresh 로드 값이 아니라 트리거 시점에 고정된 키로 받는다 — 트리거~시작 사이 교체가 끼어들어
			// 옛 태스크가 새 키를 읽고 새 태스크와 이중 인코딩하는 변종을 닫는다 (MSG-241).
			download(originalKey, original);

			double duration = ffmpegRunner.probeDurationSec(original);
			if (duration > MAX_DURATION_SEC) {
				log.warn("영상 길이 초과로 인코딩 중단: videoId={} duration={}s", videoId, duration);
				statusWriter.markFailed(videoId, originalKey);
				return;
			}

			Path encoded = workDir.resolve("encoded.mp4");
			Path thumbnail = workDir.resolve("thumb.jpg");
			ffmpegRunner.encode720p(original, encoded);
			// AI 활성이면 썸네일은 블러 후에 폴러가 뽑는다 — 여기선 만들지도 올리지도 않는다(P1, 미블러 노출 차단).
			if (!aiEnabled) {
				ffmpegRunner.extractThumbnail(original, thumbnail, duration);
			}

			// ffmpeg 가 도는 동안 삭제됐으면 결과를 올려봐야 아무도 참조하지 않는 고아가 되고,
			// 교체됐으면 옛 바이트가 결정적 encoded 키의 새 인코딩본을 덮는다 (MSG-241) — 정체성이
			// 유지될 때(ACTIVE·같은 원본)만 올린다.
			// ponytail: 이 확인과 upload 사이 창(~100ms)은 남는다. 10초짜리 인코딩 창을 그만큼 줄이는 걸로 충분
			// — 상태 전이는 어차피 statusWriter 의 DB 가드가 막고, 새 태스크의 재인코딩이 같은 키를 덮는다.
			if (!isCurrentEncodingAttempt(videoId, originalKey)) {
				log.info("인코딩 중 삭제·교체됨 — 결과 업로드 생략: videoId={}", videoId);
				return;
			}

			String encodedKey = "videos/encoded/%d/%d.mp4".formatted(video.getUserId(), videoId);
			String thumbnailKey = "videos/thumb/%d/%d.jpg".formatted(video.getUserId(), videoId);
			upload(encodedKey, "video/mp4", encoded);

			if (aiEnabled) {
				// 미블러 썸네일을 S3 에 올리지 않고 thumbnailUrl 도 기록하지 않는다(P1/R5 불변식) — 폴러가 완료 시
				// 블러본에서 뽑아 결정적 키에 올린 뒤 그때 thumbnailUrl 을 기록한다. 교체돼도 미블러본이 공개 키에 안 닿는다.
				statusWriter.markEncoded(videoId, originalKey, encodedKey);
			} else {
				upload(thumbnailKey, "image/jpeg", thumbnail);
				statusWriter.markReady(videoId, originalKey, encodedKey, thumbnailKey);
			}
			log.info("인코딩 완료: videoId={} duration={}s aiEnabled={}", videoId, duration, aiEnabled);
		} catch (Exception e) {
			// 비동기라 던져봐야 받을 곳이 없다. 기록만 남기고 재시도하지 않는다 (MSG-65 D8).
			log.error("인코딩 실패: videoId={}", videoId, e);
			statusWriter.markFailed(videoId, originalKey);
		} finally {
			deleteQuietly(workDir);
		}
	}

	/** 업로드 직전 fresh 재확인 — VideoStatusWriter.isCurrentEncodingAttempt 와 같은 술어다 (MSG-241). */
	private boolean isCurrentEncodingAttempt(Long videoId, String originalKey) {
		return videoRepository.findById(videoId)
			.map(fresh -> fresh.getStatus() == VideoStatus.ACTIVE && originalKey.equals(fresh.getOriginalS3Key()))
			.orElse(false);
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
