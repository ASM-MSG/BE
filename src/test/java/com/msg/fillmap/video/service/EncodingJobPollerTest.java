package com.msg.fillmap.video.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.msg.fillmap.video.config.EncodingJobProperties;
import com.msg.fillmap.video.repository.VideoEncodingJobRepository;

@DisplayName("EncodingJobPoller 작업 실행과 정상 종료")
class EncodingJobPollerTest {

	private static final Duration LEASE = Duration.ofMinutes(35);
	private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

	private VideoEncodingJobRepository repository;
	private VideoEncodingService encodingService;
	private VideoStatusWriter statusWriter;
	private ThreadPoolTaskExecutor encodingExecutor;
	private EncodingJobPoller poller;
	private EncodingJobClaim claim;

	@BeforeEach
	void setUp() {
		repository = mock(VideoEncodingJobRepository.class);
		encodingService = mock(VideoEncodingService.class);
		statusWriter = mock(VideoStatusWriter.class);
		encodingExecutor = mock(ThreadPoolTaskExecutor.class);
		EncodingJobProperties properties = new EncodingJobProperties(true, Duration.ofSeconds(1),
			RETRY_DELAY, LEASE);
		poller = new EncodingJobPoller(
			repository, encodingService, statusWriter, encodingExecutor, properties, "be");
		claim = new EncodingJobClaim(1L, 7L, "videos/original/1/x.mp4", UUID.randomUUID(),
			(short) 1, LocalDateTime.of(2026, 8, 27, 0, 0));
	}

	@Test
	@DisplayName("로컬 실행 중이면 새 작업을 선점하지 않는다")
	void 로컬_실행중이면_새_작업을_선점하지_않는다() {
		given(repository.claimNext(eq("be"), any(UUID.class), eq(LEASE))).willReturn(Optional.of(claim));

		poller.poll();
		poller.poll();

		verify(repository, times(1)).claimNext(eq("be"), any(UUID.class), eq(LEASE));
	}

	@Test
	@DisplayName("빈 노드는 한 건만 선점해 encodingExecutor에 제출한다")
	void 빈_노드는_한건만_선점해_encodingExecutor에_제출한다() {
		given(repository.claimNext(eq("be"), any(UUID.class), eq(LEASE))).willReturn(Optional.of(claim));

		poller.poll();
		runSubmittedTask();

		verify(encodingService).encode(claim);
		verify(repository, never()).complete(claim);
	}

	@Test
	@DisplayName("실행기 거부는 시도 횟수를 소비하지 않고 작업을 반납한다")
	void 실행기_거부는_시도횟수를_소비하지_않고_작업을_반납한다() {
		given(repository.claimNext(eq("be"), any(UUID.class), eq(LEASE))).willReturn(Optional.of(claim));
		willThrow(new RejectedExecutionException("full")).given(encodingExecutor).execute(any(Runnable.class));

		poller.poll();

		verify(repository).release(claim);
		verify(repository, never()).retry(any(), any(), any());
	}

	@Test
	@DisplayName("첫 번째와 두 번째 처리 오류는 5초 뒤 재시도한다")
	void 첫번째와_두번째_처리오류는_5초뒤_재시도한다() {
		given(repository.claimNext(eq("be"), any(UUID.class), eq(LEASE))).willReturn(Optional.of(claim));
		willThrow(new IllegalStateException("S3 unavailable")).given(encodingService).encode(claim);

		poller.poll();
		runSubmittedTask();

		verify(repository).retry(claim, RETRY_DELAY, "S3 unavailable");
		verify(repository, never()).complete(claim);
	}

	@Test
	@DisplayName("세 번째 처리 오류는 영상과 작업을 FAILED로 종결한다")
	void 세번째_처리오류는_영상과_작업을_FAILED로_종결한다() {
		claim = new EncodingJobClaim(1L, 7L, "videos/original/1/x.mp4", UUID.randomUUID(),
			(short) 3, LocalDateTime.of(2026, 8, 27, 0, 0));
		given(repository.claimDead(eq("be"), any(UUID.class), eq(LEASE))).willReturn(Optional.empty());
		given(repository.claimNext(eq("be"), any(UUID.class), eq(LEASE))).willReturn(Optional.of(claim));
		willThrow(new IllegalStateException("ffmpeg unavailable")).given(encodingService).encode(claim);

		poller.poll();
		runSubmittedTask();

		verify(statusWriter).markFailed(claim);
		verify(repository, never()).retry(any(), any(), any());
	}

	@Test
	@DisplayName("claim을 잃은 결과는 재시도나 실패 종결을 하지 않는다")
	void claim을_잃은_결과는_무시한다() {
		given(repository.claimNext(eq("be"), any(UUID.class), eq(LEASE))).willReturn(Optional.of(claim));
		willThrow(new ClaimLostException(claim.jobId())).given(encodingService).encode(claim);

		poller.poll();
		runSubmittedTask();

		verify(repository, never()).retry(any(), any(), any());
		verify(statusWriter, never()).markFailed(any(EncodingJobClaim.class));
	}

	@Test
	@DisplayName("세 번째 시도 중 중단된 DEAD 작업도 FAILED로 종결한다")
	void 세번째_시도중_중단된_DEAD_작업도_FAILED로_종결한다() {
		EncodingJobClaim deadClaim = new EncodingJobClaim(
			2L, 8L, "videos/original/1/dead.mp4", UUID.randomUUID(),
			(short) 3, LocalDateTime.of(2026, 8, 27, 0, 0));
		given(repository.claimDead(eq("be"), any(UUID.class), eq(LEASE)))
			.willReturn(Optional.of(deadClaim));

		poller.poll();
		runSubmittedTask();

		verify(repository).markExpiredThirdAttemptsDead();
		verify(statusWriter).markDeadFailed(deadClaim);
		verify(repository, never()).claimNext(any(), any(), any());
	}

	@Test
	@DisplayName("SIGTERM이면 현재 claim을 즉시 반납하고 새 작업을 받지 않는다")
	void SIGTERM이면_현재_claim을_즉시_반납하고_새_작업을_받지_않는다() {
		given(repository.claimNext(eq("be"), any(UUID.class), eq(LEASE))).willReturn(Optional.of(claim));
		poller.poll();

		poller.shutdown();
		poller.poll();
		runSubmittedTask();

		verify(repository).release(claim);
		verify(repository, times(1)).claimNext(eq("be"), any(UUID.class), eq(LEASE));
		verify(encodingService, never()).encode(claim);
	}

	private void runSubmittedTask() {
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(encodingExecutor).execute(task.capture());
		task.getValue().run();
	}
}
