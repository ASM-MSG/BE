package com.msg.fillmap.video.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 인코딩 상태 전이를 각각 독립 트랜잭션으로 커밋한다.
 *
 * 별도 빈으로 둔 이유: @Transactional 은 프록시로 동작하므로 같은 클래스 안에서 호출하면(self-invocation)
 * 프록시를 거치지 않아 REQUIRES_NEW 가 무시된다. 그러면 ENCODING 이 DB 에 보이지 않고, 실패 시
 * markFailed 까지 함께 롤백돼 UPLOADED 로 남는다.
 *
 * 폴러 전용 쓰기(markBlurReady·markBlurFailed·recordAiJob)는 교체/삭제와 벌어진 창을 막기 위해
 * findWithLockById(행 잠금)로 fresh 조회한 뒤 잡 정체성을 재확인하고(P1/P2), 불일치면 조용히 skip 한다.
 *
 * 인코딩 태스크 쓰기(markEncoding·markReady·markEncoded·markFailed)도 같은 패턴으로 지킨다 (MSG-241) —
 * 시도 정체성은 "어느 원본을 인코딩했나" = original_s3_key 다. 교체가 키를 갈아끼우므로(UNIQUE, V2)
 * 키 불일치 = 다른 시도이고, 옛 태스크의 완료/실패가 새 파일 상태(UPLOADED→재인코딩)를 덮지 못한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatusWriter {

	private final VideoRepository videoRepository;

	/** 적용 여부를 반환한다 — false 면 태스크 시작 시점부터 스테일(큐 대기 중 교체·삭제됨)이라 ffmpeg 을 돌릴 이유가 없다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markEncoding(Long videoId, String expectedOriginalKey) {
		Video video = videoRepository.findWithLockById(videoId).orElse(null);
		if (!isCurrentEncodingAttempt(video, expectedOriginalKey)) {
			logStaleSkip("인코딩 시작", videoId, expectedOriginalKey, video);
			return false;
		}
		video.markEncoding();
		return true;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markReady(Long videoId, String expectedOriginalKey, String encodedKey, String thumbnailKey) {
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentEncodingAttempt(video, expectedOriginalKey)) {
				logStaleSkip("인코딩 완료", videoId, expectedOriginalKey, video);
				return;
			}
			video.markReady(encodedKey, thumbnailKey);
		});
	}

	/** AI 활성 인코딩 완료 지점 (MSG-149) — READY 대신 BLURRING. thumbnailUrl 은 폴러가 완료 시 기록한다(P2). */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markEncoded(Long videoId, String expectedOriginalKey, String encodedKey) {
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentEncodingAttempt(video, expectedOriginalKey)) {
				// 스테일 markEncoded 는 blurringStartedAt 을 새로 찍어 폴러 가드를 정상 통과시키므로(MSG-241)
				// 여기서 못 막으면 옛 파일의 ai_job_id 가 새 시도에 붙는다.
				logStaleSkip("인코딩 완료(AI)", videoId, expectedOriginalKey, video);
				return;
			}
			video.markEncoded(encodedKey);
		});
	}

	/**
	 * 제출 직후 job_id 기록 (MSG-149, P1-b). 폴러가 목록 로드 시점의 blurringStartedAt(시도 넌스)을 넘긴다.
	 * fresh 조회가 여전히 그 시도(ACTIVE·BLURRING·아직 미제출·같은 blurringStartedAt)일 때만 기록한다 —
	 * 교체로 파일이 바뀌었으면 옛 파일의 잡ID 를 새 시도에 붙이지 않는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordAiJob(Long videoId, String jobId, LocalDateTime expectedStartedAt) {
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentAttempt(video, expectedStartedAt)) {
				logStaleSkip("잡ID 기록", videoId, jobId, video);
				return;
			}
			video.recordAiJob(jobId);
		});
	}

	/**
	 * AI DONE 결과 반영 (MSG-150) — 블러본·하이라이트를 채우고 BLURRING → READY 로 전이한다.
	 * 폴러가 BLURRING 목록을 읽은 뒤 다운로드/업로드하는 사이 사용자가 교체(replaceFile)·삭제하면
	 * 이 영상은 더는 그 잡의 결과 대상이 아니다. 행 잠금 후 잡 정체성(ACTIVE·BLURRING·같은 ai_job_id)이
	 * 유지될 때만 적용한다 (P1/P2). 적용 여부를 반환해 폴러가 거부 시 올린 블러본·썸네일을 지운다 (P2-d).
	 * thumbnailKey 는 폴러가 방금 S3 에 올린 썸네일 키 — 여기서 thumbnailUrl 에 기록해 불변식을 지킨다 (P2 R5).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markBlurReady(Long videoId, String expectedJobId, String blurredS3Key, String thumbnailKey,
		List<List<Double>> highlights) {
		Video video = videoRepository.findWithLockById(videoId).orElse(null);
		if (!isCurrentBlurJob(video, expectedJobId)) {
			logStaleSkip("블러 결과", videoId, expectedJobId, video);
			return false;
		}
		video.applyBlurResult(blurredS3Key, highlights);
		video.markReadyFromBlurring(thumbnailKey);
		return true;
	}

	/**
	 * AI 실패/유실/타임아웃을 FAILED 로 수렴 (MSG-150). markFailed 와 달리 폴러 전용이라 행 잠금 + 잡 정체성
	 * 가드를 건다 — 교체/삭제로 이미 대상이 아니게 된 영상을 옛 잡 판단으로 FAILED 로 밀지 않는다 (P1/P2).
	 * jobId 와 함께 blurringStartedAt(시도 넌스)도 확인한다 (P2-a) — 미제출 행(jobId null)끼리는 jobId 로
	 * 구분되지 않으므로 recordAiJob 과 대칭으로 시도 시작시각까지 일치해야 한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markBlurFailed(Long videoId, String expectedJobId, LocalDateTime expectedStartedAt) {
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentBlurJob(video, expectedJobId)
				|| !Objects.equals(video.getBlurringStartedAt(), expectedStartedAt)) {
				logStaleSkip("블러 실패", videoId, expectedJobId, video);
				return;
			}
			video.markFailed();
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(Long videoId, String expectedOriginalKey) {
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentEncodingAttempt(video, expectedOriginalKey)) {
				// 교체 afterCommit 이 옛 원본을 S3 에서 지워 옛 태스크의 다운로드가 실패하는 게 대표 경로 —
				// 그 실패는 옛 시도의 것이니 새 시도의 UPLOADED/ENCODING 을 FAILED 로 밀지 않는다 (MSG-241).
				logStaleSkip("인코딩 실패", videoId, expectedOriginalKey, video);
				return;
			}
			video.markFailed();
		});
	}

	/** 이 태스크가 인코딩한 원본과 현재 DB 행이 같은 시도인가 — 교체(키 갱신)/삭제(ACTIVE 이탈)로 벌어진 창을 닫는다. */
	private boolean isCurrentEncodingAttempt(Video video, String expectedOriginalKey) {
		return video != null
			&& video.getStatus() == VideoStatus.ACTIVE
			&& Objects.equals(video.getOriginalS3Key(), expectedOriginalKey);
	}

	/** 폴링/완료/실패 판단의 잡과 현재 DB 엔티티가 같은 잡인가 — 교체/삭제로 벌어진 창을 닫는다. */
	private boolean isCurrentBlurJob(Video video, String expectedJobId) {
		return video != null
			&& video.getStatus() == VideoStatus.ACTIVE
			&& video.getProcessingStatus() == ProcessingStatus.BLURRING
			&& Objects.equals(video.getAiJobId(), expectedJobId);
	}

	/** 제출 판단의 시도와 현재 DB 엔티티가 같은 시도인가 — job_id 가 아직 null 이라 blurringStartedAt 넌스로 식별. */
	private boolean isCurrentAttempt(Video video, LocalDateTime expectedStartedAt) {
		return video.getStatus() == VideoStatus.ACTIVE
			&& video.getProcessingStatus() == ProcessingStatus.BLURRING
			&& video.getAiJobId() == null
			&& Objects.equals(video.getBlurringStartedAt(), expectedStartedAt);
	}

	private void logStaleSkip(String what, Long videoId, String expected, Video video) {
		log.info("스테일 {} 무시 — 잡 정체성 불일치: videoId={} expected={} status={} ps={} aiJobId={} startedAt={}",
			what, videoId, expected,
			video == null ? null : video.getStatus(),
			video == null ? null : video.getProcessingStatus(),
			video == null ? null : video.getAiJobId(),
			video == null ? null : video.getBlurringStartedAt());
	}
}
