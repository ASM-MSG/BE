package com.msg.fillmap.video.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.config.AiProperties;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.FfmpegRunner;
import com.msg.fillmap.video.support.VideoAssetKeys;

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
	// 인코딩 태스크 결과 계측 (MSG-343) — completed·failed_over_duration·failed_error.
	private final VideoProcessingMetrics videoProcessingMetrics;
	// 실효 블러 활성(enabled && blurEnabled) 판정 재료 (MSG-456) — 플래그가 2개라 @Value 산발 대신 한 타입으로 읽는다.
	private final AiProperties aiProperties;
	// AiClient 는 ai.enabled 일 때만 뜨는 빈이라 직접 주입하면 비활성 환경에서 기동이 깨진다 (HighlightPreview 선례).
	private final ObjectProvider<AiClient> aiClientProvider;
	// 후행 하이라이트 계산 전용 풀 (MSG-456 D-1). 구체 타입이 둘이라 필드명=빈 이름 매칭으로 갈린다 (AsyncConfig).
	private final ThreadPoolTaskExecutor highlightExecutor;

	@Override
	public void encode(EncodingJobClaim claim) {
		Long videoId = claim.videoId();
		String originalKey = claim.originalS3Key();
		if (!statusWriter.markEncoding(claim)) {
			return;
		}
		Video video = videoRepository.findById(videoId).orElse(null);
		if (video == null) {
			log.error("인코딩 대상 영상 없음: videoId={}", videoId);
			statusWriter.complete(claim);
			return;
		}

		Path workDir = null;
		// 태스크당 result 카운트 정확히 1회 (Codex 2R) — over_duration 계상 후 markFailed 가 던져
		// outer catch 로 떨어져도 failed_error 로 이중 계상하지 않는다.
		boolean resultCounted = false;
		try {
			// 큐 대기 중 교체/삭제됐으면 이 태스크의 원본은 더는 현재 시도가 아니다 — ffmpeg 을 돌리지 않는다 (MSG-241).
			workDir = Files.createTempDirectory("encode-" + videoId + "-");

			Path original = workDir.resolve("original");
			// fresh 로드 값이 아니라 트리거 시점에 고정된 키로 받는다 — 트리거~시작 사이 교체가 끼어들어
			// 옛 태스크가 새 키를 읽고 새 태스크와 이중 인코딩하는 변종을 닫는다 (MSG-241).
			download(originalKey, original);

			double duration = ffmpegRunner.probeDurationSec(original);
			if (duration > MAX_DURATION_SEC) {
				log.warn("영상 길이 초과로 인코딩 중단: videoId={} duration={}s", videoId, duration);
				// 분류 보존 — markFailed(REQUIRES_NEW)가 던져도 over_duration 계상은 이미 끝나 있어야 한다.
				videoProcessingMetrics.countEncodingTask(VideoProcessingMetrics.TASK_FAILED_OVER_DURATION);
				resultCounted = true;
				statusWriter.markFailed(claim);
				return;
			}

			// 저장되는 길이의 정본 (MSG-470) — 클라 신고값은 확정 시점 잠정값이고, 여기서 실측 반올림으로 덮는다.
			// 1~30 클램프는 스키마 CHECK(duration_sec > 0 AND <= 30) 준수용이다: 30~31초 여유 구간(위 MAX_DURATION_SEC
			// 주석)은 반올림하면 31이 되고, 0.5초 미만 영상은 0이 된다. 초과 실패 판정은 위에서 클램프 전 원값으로 끝냈다.
			short measuredDurationSec = (short) Math.max(1, Math.min(30, Math.round(duration)));

			Path encoded = workDir.resolve("encoded.mp4");
			Path thumbnail = workDir.resolve("thumb.jpg");
			ffmpegRunner.encode720p(original, encoded);
			// 실효 블러 활성 (MSG-456) — 블러는 AiClient(ai.enabled 게이트)에 의존해 단독 플래그로는 못 켠다.
			boolean blurActive = aiProperties.enabled() && aiProperties.blurEnabled();
			// 블러 활성이면 썸네일은 블러 후에 폴러가 뽑는다 — 여기선 만들지도 올리지도 않는다(P1, 미블러 노출 차단).
			if (!blurActive) {
				ffmpegRunner.extractThumbnail(original, thumbnail, duration);
			}

			// ffmpeg 가 도는 동안 삭제·교체됐으면 결과를 올려봐야 아무도 참조하지 않는 고아가 된다.
			// 정체성이 유지될 때(ACTIVE·같은 원본)만 올린다 (MSG-241).
			// ponytail: 이 확인과 upload 사이 창(~100ms)은 남는다. 10초짜리 인코딩 창을 그만큼 줄이는 걸로 충분
			// — DB 가드가 상태 전이를 막고, 교체 시도는 별도 키를 써 현재 산출물을 보존한다.
			if (!isCurrentEncodingAttempt(videoId, originalKey)) {
				log.info("인코딩 중 삭제·교체됨 — 결과 업로드 생략: videoId={}", videoId);
				statusWriter.complete(claim);
				return;
			}

			VideoAssetKeys assetKeys = VideoAssetKeys.from(video.getUserId(), videoId, originalKey);
			String encodedKey = assetKeys.encoded();
			String thumbnailKey = assetKeys.thumbnail();
			upload(encodedKey, "video/mp4", encoded);

			if (blurActive) {
				// 미블러 썸네일을 S3 에 올리지 않고 thumbnailUrl 도 기록하지 않는다(P1/R5 불변식) — 폴러가 완료 시
				// 블러본에서 뽑아 결정적 키에 올린 뒤 그때 thumbnailUrl 을 기록한다. 교체돼도 미블러본이 공개 키에 안 닿는다.
				statusWriter.markEncoded(claim, encodedKey, measuredDurationSec);
				videoProcessingMetrics.countEncodingTask(VideoProcessingMetrics.TASK_COMPLETED);
				resultCounted = true;
			} else {
				// 블러 꺼짐 경로. READY 전이와 완료 계측을 먼저 끝내고, 하이라이트는 전용 워커에 넘긴다 (MSG-456 D-1)
				// — 계상 먼저(over_duration 경로와 같은 결). 워커 본문의 실패는 인코딩 태스크 계측과 무관하다.
				upload(thumbnailKey, "image/jpeg", thumbnail);
				statusWriter.markReady(claim, encodedKey, thumbnailKey, measuredDurationSec);
				videoProcessingMetrics.countEncodingTask(VideoProcessingMetrics.TASK_COMPLETED);
				resultCounted = true;
				submitHighlightJob(videoId, originalKey, encodedKey);
			}
			log.info("인코딩 완료: videoId={} duration={}s blurActive={}", videoId, duration, blurActive);
		} catch (ClaimLostException e) {
			throw e;
		} catch (FfmpegRunner.InvalidMediaException e) {
			log.warn("손상 영상으로 인코딩 중단: videoId={}", videoId, e);
			if (!resultCounted) {
				videoProcessingMetrics.countEncodingTask(VideoProcessingMetrics.TASK_FAILED_ERROR);
			}
			statusWriter.markFailed(claim);
		} catch (Exception e) {
			log.error("인코딩 실패: videoId={}", videoId, e);
			if (!resultCounted) {
				videoProcessingMetrics.countEncodingTask(VideoProcessingMetrics.TASK_FAILED_ERROR);
			}
			if (e instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("인코딩 실행 실패", e);
		} finally {
			deleteQuietly(workDir);
		}
	}

	/**
	 * 업로드 직전 fresh 재확인 — VideoStatusWriter.isCurrentEncodingAttempt 와 같은 술어다 (MSG-241).
	 * 인코딩 국면(UPLOADED·ENCODING) 조건도 라이터와 함께 유지한다 (MSG-382) — 이중 트리거로 다른 태스크가
	 * 먼저 종결시켰으면 이 태스크의 결과 업로드도 의미가 없다.
	 */
	private boolean isCurrentEncodingAttempt(Long videoId, String originalKey) {
		return videoRepository.findById(videoId)
			.map(fresh -> fresh.getStatus() == VideoStatus.ACTIVE
				&& (fresh.getProcessingStatus() == ProcessingStatus.UPLOADED
					|| fresh.getProcessingStatus() == ProcessingStatus.ENCODING)
				&& originalKey.equals(fresh.getOriginalS3Key()))
			.orElse(false);
	}

	/** 워커 제출 (MSG-456 FR-8, D-1). 큐 포화 거부는 폐기가 정책이다 — 그 영상은 하이라이트 null 로 남는다. */
	private void submitHighlightJob(Long videoId, String originalKey, String encodedKey) {
		if (aiClientProvider.getIfAvailable() == null) {
			return;   // ai.enabled=false — 지금과 동일 (FR-5)
		}
		try {
			highlightExecutor.execute(() -> computeAndRecordHighlights(videoId, originalKey, encodedKey));
		} catch (RejectedExecutionException e) {
			log.warn("하이라이트 큐 포화로 폐기. 하이라이트 없이 둔다: videoId={}", videoId);
		}
	}

	/**
	 * 워커 본문 (MSG-456 D-1). markReady 시점에 결정적 키로 이미 올라간 S3 인코딩본을 파일로 받아 분석한다 —
	 * 인코딩 tmp 의 수명이 태스크 경계를 넘지 않도록 워커는 자기 임시 파일을 스스로 만들고 지운다.
	 * 어떤 실패도 하이라이트 부재로만 남는다(예외 구분 없음) — catch 는 스레드 풀이 예외를 조용히 삼키는 것을
	 * 막고 실패를 로그로 남기기 위한 것이기도 하다. 교체·삭제로 스테일이면 recordHighlights 가드가 버린다.
	 */
	private void computeAndRecordHighlights(Long videoId, String originalKey, String encodedKey) {
		Path workDir = null;
		try {
			workDir = Files.createTempDirectory("highlight-" + videoId + "-");
			Path encoded = workDir.resolve("encoded.mp4");
			download(encodedKey, encoded);
			List<List<Double>> highlights = aiClientProvider.getObject().analyzeHighlights(encoded);
			statusWriter.recordHighlights(videoId, originalKey, highlights);
		} catch (Exception e) {
			log.warn("재생 하이라이트 계산 실패. 하이라이트 없이 둔다: videoId={}", videoId, e);
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
