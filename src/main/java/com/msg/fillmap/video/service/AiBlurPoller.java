package com.msg.fillmap.video.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.config.AiProperties;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.service.AiClient.AiJobResult;
import com.msg.fillmap.video.service.AiClient.AiJobStatus;
import com.msg.fillmap.video.support.FfmpegRunner;

/**
 * BLURRING 영상을 일괄 조정하는 단일 폴러 (MSG-149/150 D3). ai.enabled 일 때만 빈으로 뜨고
 * @Scheduled(fixedDelay) 로 폴 중첩 없이 돈다 — 비활성 환경(로컬/CI/현재 prod)에서는 아예 뜨지 않는다.
 *
 * 잡별 reconcile:
 *  - 미제출(job_id null) → 타임아웃 넘었으면 markBlurFailed, 아니면 encoded 재다운로드 후 제출·job_id 기록 (D2)
 *  - 제출됨 → 먼저 GET /jobs/{id} 폴링. QUEUED/PROCESSING=살아있음(타임아웃 skip), DONE=완료, FAILED/404=markBlurFailed
 *  - poll 불가(연결 실패)·미제출 행에만 blurringStartedAt 기준 타임아웃 적용 (D4). 그 외 예외는 잡별 catch 로 BLURRING 유지
 *
 * 완료 시: 블러본을 S3 에 올리고, 인코딩 시 만든 미블러 썸네일을 블러본에서 다시 뽑아 같은 키에 덮어쓴 뒤
 * (P1, 에픽 목적) markBlurReady 로 전이한다. 완료/실패 쓰기는 잡 정체성(jobId·blurringStartedAt)을 넘겨
 * VideoStatusWriter 가 행 잠금 후 재확인하고, 교체/삭제로 벌어진 창이면 skip 한다 (P1/P2).
 *
 * ponytail: 단일 인스턴스 전제 — 이 폴러는 앱 인스턴스가 1개일 때만 안전하다 (encodingExecutor 의 단일
 * 프로세스 OOM 직렬화와 같은 전제, ADR t3.small). 수평 확장 시 두 폴러가 같은 행을 집어 패자가 승자의 S3
 * 산출물을 지울 수 있다 — 그때는 조회에 FOR UPDATE SKIP LOCKED 클레임 또는 시도 스코프 키로 승격할 것.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled")
@RequiredArgsConstructor
public class AiBlurPoller {

	private final VideoRepository videoRepository;
	private final VideoStatusWriter statusWriter;
	private final AiClient aiClient;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;
	private final AiProperties aiProperties;
	private final FfmpegRunner ffmpegRunner;
	// 인코딩 워커와 같은 단일 풀 — 썸네일 재추출 ffmpeg 를 여기 태워 t3.small OOM 을 막는다 (P2-a).
	// 구체 타입 주입: @Scheduled 가 만드는 taskScheduler 도 Executor 라 by-type 이 모호하므로.
	private final ThreadPoolTaskExecutor encodingExecutor;

	@Scheduled(fixedDelayString = "${ai.poll-interval-ms:30000}")
	public void reconcile() {
		List<Video> targets = videoRepository.findByStatusAndProcessingStatus(
			VideoStatus.ACTIVE, ProcessingStatus.BLURRING);
		for (Video video : targets) {
			try {
				reconcileOne(video);
			} catch (Exception e) {
				// 연결 거부·ffmpeg 실패 등 — 상태를 건드리지 않아 BLURRING 을 유지하고 다음 주기에 재시도한다.
				log.warn("AI reconcile 실패 — BLURRING 유지: videoId={}", video.getId(), e);
			}
		}
	}

	private void reconcileOne(Video video) {
		if (video.getAiJobId() == null) {
			// 미제출: 타임아웃 상한을 넘겼으면 포기, 아니면 제출한다.
			if (failIfTimedOut(video)) {
				return;
			}
			submit(video);
			return;
		}
		poll(video);
	}

	/** encoded_url 키를 S3 에서 재다운로드해 제출한다 (D2 — 인코딩 tmp 는 이미 삭제됨). */
	private void submit(Video video) {
		byte[] encoded = download(video.getEncodedUrl());
		String jobId = aiClient.submit(encoded);
		// ponytail: 크래시 창에서 중복 제출 1건 가능 — AI API에 멱등 키 생기면 적용
		statusWriter.recordAiJob(video.getId(), jobId, video.getBlurringStartedAt());
		log.info("AI 제출 완료: videoId={} jobId={}", video.getId(), jobId);
	}

	private void poll(Video video) {
		AiJobResult result;
		try {
			result = aiClient.poll(video.getAiJobId());
		} catch (RuntimeException e) {
			// poll 이 상태를 못 만든 모든 경우(연결 실패·200 malformed body 파싱 실패 등) → 살아있는지 알 수 없다.
			// 타임아웃 넘었으면 포기, 아니면 다음 주기 재시도. 넓게 잡아 미래의 파싱 구멍까지 타임아웃 경로로 닫는다 (P2-a).
			if (!failIfTimedOut(video)) {
				log.warn("AI poll 실패 — BLURRING 유지: videoId={} jobId={}", video.getId(), video.getAiJobId(), e);
			}
			return;
		}
		if (result.notFound() || result.status() == AiJobStatus.FAILED) {
			log.warn("AI 실패/유실로 FAILED: videoId={} jobId={} notFound={}",
				video.getId(), video.getAiJobId(), result.notFound());
			statusWriter.markBlurFailed(video.getId(), video.getAiJobId(), video.getBlurringStartedAt());
			return;
		}
		if (result.status() == AiJobStatus.DONE) {
			try {
				complete(video, result);
			} catch (Exception e) {
				// DONE 소비(다운로드·추출·업로드·persist) 실패 → 영구 재시도 대신 타임아웃 경로로 수렴시킨다
				// (성공 기준 3, P1). 타임아웃 전이면 로그+다음 주기 재시도, 초과면 FAILED.
				if (!failIfTimedOut(video)) {
					log.warn("DONE 소비 실패 — BLURRING 유지: videoId={} jobId={}", video.getId(), video.getAiJobId(), e);
				}
			}
			return;
		}
		if (result.status() == AiJobStatus.UNKNOWN) {
			// 해석 불가 status → 살아있다고 볼 수 없다. 타임아웃 넘었으면 FAILED, 아니면 다음 주기 재시도
			// (배포 스큐로 인한 일시 garbage 대비 즉시 FAILED 금지). (P2-b)
			log.warn("AI status UNKNOWN — 타임아웃 경로로 라우팅: videoId={} jobId={}", video.getId(), video.getAiJobId());
			failIfTimedOut(video);
			return;
		}
		// QUEUED · PROCESSING → 살아있음. 타임아웃 검사 skip 해 큐 적체 오탐을 없앤다. 다음 주기에 다시 폴링.
		// ponytail: PROCESSING 행업은 무한 대기 — AI 재시작(인메모리 큐 소실→404)이나 AI FAILED 가 정리, 잦으면 PROCESSING 상한 추가
	}

	private void complete(Video video, AiJobResult result) {
		byte[] blurred = aiClient.downloadBlurred(video.getAiJobId());
		if (blurred == null) {
			// DONE 인데 완료본 미가용(409 지속·빈 body) → 조기 return 이 아니라 타임아웃 경로로. 초과 전이면
			// 다음 주기 재시도, 초과면 FAILED 수렴 (P2-b). 모든 폴 사이클 결말 = 성공/가드거부/failIfTimedOut.
			failIfTimedOut(video);
			return;
		}
		// 인코딩 워커가 만든 썸네일은 블러 전 원본 프레임이라 PUBLIC 전환 시 미블러 얼굴이 대표로 노출된다.
		// 블러본에서 같은 FfmpegRunner 로 다시 뽑는다 (P1). 추출은 업로드 전이라 실패해도 정리할 객체가 없다.
		byte[] thumbnail = extractThumbnail(video, blurred);
		String blurredKey = "videos/blurred/%d/%d.mp4".formatted(video.getUserId(), video.getId());
		// 썸네일 키는 인코더 규칙(VideoEncodingServiceImpl)과 동일 포맷으로 직접 계산한다 — BLURRING 동안
		// thumbnailUrl 은 null 이라(R5 불변식) 여기서 산출해 올리고, 아래 markBlurReady 가 그 키를 기록한다.
		String thumbnailKey = "videos/thumb/%d/%d.jpg".formatted(video.getUserId(), video.getId());

		boolean applied;
		try {
			upload(blurredKey, "video/mp4", blurred);
			upload(thumbnailKey, "image/jpeg", thumbnail);
			applied = statusWriter.markBlurReady(video.getId(), video.getAiJobId(), blurredKey, thumbnailKey,
				result.highlights());
		} catch (RuntimeException e) {
			// 부분 업로드(블러본만 올라간 채 썸네일/persist 실패)면 DB 미기록 객체가 고아로 남는다 — 방금
			// 계산한 키를 정리하고 전파해 상위(poll)가 타임아웃 경로로 수렴시키게 한다 (P2-a). 재업로드는 멱등.
			deleteQuietly(blurredKey, thumbnailKey);
			throw e;
		}
		if (!applied) {
			// 가드 거부(교체/삭제/스테일) → 방금 올린 블러본과 재추출 썸네일 둘 다 고아라 지운다 (P2-c/d).
			// 교체 레이스여도 안전: 인코딩 단계에서 미블러 썸네일을 안 올렸고(P1), 새 시도가 완료 시 자기 걸 올린다.
			deleteQuietly(blurredKey, thumbnailKey);
			return;
		}
		log.info("AI 블러 완료: videoId={} blurredKey={} highlights={}",
			video.getId(), blurredKey, result.highlights());
	}

	private boolean failIfTimedOut(Video video) {
		if (!isTimedOut(video)) {
			return false;
		}
		log.warn("AI 처리 타임아웃 초과로 FAILED: videoId={} startedAt={}", video.getId(), video.getBlurringStartedAt());
		statusWriter.markBlurFailed(video.getId(), video.getAiJobId(), video.getBlurringStartedAt());
		return true;
	}

	/** 타임아웃은 행 생성(created_at)이 아니라 이번 BLURRING 시도 시작 기준 (D4 V4). 옛 행 재시도 오탐 방지. */
	private boolean isTimedOut(Video video) {
		LocalDateTime start = video.getBlurringStartedAt() != null ? video.getBlurringStartedAt() : video.getCreatedAt();
		return Duration.between(start, LocalDateTime.now()).compareTo(aiProperties.timeout()) > 0;
	}

	/**
	 * 블러본 bytes 에서 썸네일을 뽑아 bytes 로 돌려준다. ffmpeg 실행은 인코딩 워커와 같은 encodingExecutor 에
	 * 태워 직렬화한다 (P2-a) — t3.small 에서 동시 ffmpeg 가 OOM 을 부르기 때문이다. 단일 폴러·fixedDelay 라
	 * 폴러 스레드가 인코딩 큐를 기다려도 안전하다. ffmpeg 실패는 원인을 그대로 올려 이 사이클을 skip 시킨다.
	 */
	private byte[] extractThumbnail(Video video, byte[] blurred) {
		try {
			return encodingExecutor.submit(() -> runThumbnail(video, blurred)).get();
		} catch (ExecutionException e) {
			throw new IllegalStateException("블러본 썸네일 재추출 실패: videoId=" + video.getId(), e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("블러본 썸네일 재추출 대기 인터럽트: videoId=" + video.getId(), e);
		}
	}

	/** 임시파일에 블러본을 쓰고 인코딩 워커와 같은 FfmpegRunner 로 프레임을 뽑는다 (encodingExecutor 스레드에서 실행). */
	private byte[] runThumbnail(Video video, byte[] blurred) throws IOException {
		Path dir = null;
		try {
			dir = Files.createTempDirectory("blur-thumb-" + video.getId() + "-");
			Path blurredFile = dir.resolve("blurred.mp4");
			Path thumbFile = dir.resolve("thumb.jpg");
			Files.write(blurredFile, blurred);
			// 클라 신고 durationSec 대신 tmp 블러본을 실측 probe 한다 — 인코더와 동일 로직·의도로 seek 지점을 잡는다 (P2-b).
			double duration = ffmpegRunner.probeDurationSec(blurredFile);
			ffmpegRunner.extractThumbnail(blurredFile, thumbFile, duration);
			return Files.readAllBytes(thumbFile);
		} finally {
			deleteTempDir(dir);
		}
	}

	private byte[] download(String s3Key) {
		return s3Client.getObjectAsBytes(
			GetObjectRequest.builder().bucket(awsProperties.s3().bucket()).key(s3Key).build()).asByteArray();
	}

	private void upload(String s3Key, String contentType, byte[] bytes) {
		s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.key(s3Key)
				.contentType(contentType)
				.build(),
			RequestBody.fromBytes(bytes));
	}

	/** 가드 거부로 고아가 된 객체 정리(블러본·재추출 썸네일). 실패해도 비용·위생 문제라 로그만 (VideoServiceImpl 관례). */
	private void deleteQuietly(String... s3Keys) {
		for (String s3Key : s3Keys) {
			try {
				s3Client.deleteObject(DeleteObjectRequest.builder()
					.bucket(awsProperties.s3().bucket())
					.key(s3Key)
					.build());
			} catch (SdkException e) {
				log.warn("가드 거부 정리 실패 — 고아로 남는다: key={}", s3Key, e);
			}
		}
	}

	/** 썸네일 재추출용 임시 디렉터리 정리 (VideoEncodingServiceImpl 관례). 삭제 실패는 로그만. */
	private void deleteTempDir(Path dir) {
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
