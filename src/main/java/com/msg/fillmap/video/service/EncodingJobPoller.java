package com.msg.fillmap.video.service;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.video.config.AsyncConfig;
import com.msg.fillmap.video.config.EncodingJobProperties;
import com.msg.fillmap.video.repository.VideoEncodingJobRepository;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "fillmap.video.encoding-job", name = "enabled", havingValue = "true",
	matchIfMissing = true)
public class EncodingJobPoller {

	private final VideoEncodingJobRepository repository;
	private final VideoEncodingService encodingService;
	private final VideoStatusWriter statusWriter;
	private final ThreadPoolTaskExecutor encodingExecutor;
	private final EncodingJobProperties properties;
	private final String nodeId;
	private final AtomicBoolean busy = new AtomicBoolean(false);
	private final AtomicBoolean acceptingClaims = new AtomicBoolean(true);
	private final AtomicReference<EncodingJobClaim> activeClaim = new AtomicReference<>();

	public EncodingJobPoller(VideoEncodingJobRepository repository, VideoEncodingService encodingService,
		VideoStatusWriter statusWriter,
		@Qualifier(AsyncConfig.ENCODING_EXECUTOR) ThreadPoolTaskExecutor encodingExecutor,
		EncodingJobProperties properties, @Value("${FILLMAP_NODE_ID:be}") String nodeId) {
		this.repository = repository;
		this.encodingService = encodingService;
		this.statusWriter = statusWriter;
		this.encodingExecutor = encodingExecutor;
		this.properties = properties;
		this.nodeId = nodeId;
	}

	@Scheduled(fixedDelayString = "${fillmap.video.encoding-job.poll-interval:PT1S}")
	public void poll() {
		if (!acceptingClaims.get() || !busy.compareAndSet(false, true)) {
			return;
		}
		try {
			repository.markExpiredThirdAttemptsDead();
			EncodingJobClaim deadClaim = repository.claimDead(
				nodeId, UUID.randomUUID(), properties.leaseDuration()).orElse(null);
			if (deadClaim != null) {
				submit(deadClaim, () -> statusWriter.markDeadFailed(deadClaim));
				return;
			}
			EncodingJobClaim claim = repository.claimNext(
				nodeId, UUID.randomUUID(), properties.leaseDuration()).orElse(null);
			if (claim == null) {
				busy.set(false);
				return;
			}
			submit(claim, () -> process(claim));
		} catch (RuntimeException e) {
			busy.set(false);
			log.warn("인코딩 작업 선점 실패: node={}", nodeId, e);
		}
	}

	private void submit(EncodingJobClaim claim, Runnable task) {
		activeClaim.set(claim);
		try {
			encodingExecutor.execute(() -> runClaim(claim, task));
		} catch (RejectedExecutionException e) {
			repository.release(claim);
			clear(claim);
			log.warn("인코딩 실행기 포화로 작업 반납: jobId={} node={}", claim.jobId(), nodeId);
		}
	}

	private void runClaim(EncodingJobClaim claim, Runnable task) {
		if (activeClaim.get() != claim) {
			return;
		}
		try {
			task.run();
		} finally {
			clear(claim);
		}
	}

	private void process(EncodingJobClaim claim) {
		try {
			encodingService.encode(claim);
			repository.complete(claim);
		} catch (RuntimeException e) {
			if (claim.attemptCount() < 3) {
				repository.retry(claim, properties.retryDelay(), errorMessage(e));
			} else {
				statusWriter.markFailed(claim);
			}
		}
	}

	@PreDestroy
	public void shutdown() {
		acceptingClaims.set(false);
		EncodingJobClaim claim = activeClaim.getAndSet(null);
		if (claim != null) {
			repository.release(claim);
		}
		busy.set(false);
	}

	private void clear(EncodingJobClaim claim) {
		activeClaim.compareAndSet(claim, null);
		busy.set(false);
	}

	private static String errorMessage(RuntimeException error) {
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}
}
