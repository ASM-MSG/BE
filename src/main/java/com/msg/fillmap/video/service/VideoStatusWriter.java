package com.msg.fillmap.video.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.repository.VideoEncodingJobRepository;
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

	/**
	 * event_key 파일명 꼬리 상한 — notifications.event_key VARCHAR(100)에서 "VIDEO:"(6)+videoId(long 최대
	 * 19자)+":"(1)을 빼도 항상 한계 내(최대 71자). 확정 키 파일명은 {pendingUuid}-{attemptUuid}.{확장자} 77자라
	 * (VideoServiceImpl.confirmUpload) 전체를 쓰면 초과 가능. 꼬리 45자는 attemptUuid(36)+확장자를 온전히 담는다.
	 */
	private static final int EVENT_KEY_FILE_SUFFIX_MAX = 45;

	private final VideoRepository videoRepository;
	private final UserRepository userRepository;
	private final NotificationCommandService notificationCommandService;
	// 종결(READY·FAILED) 계측 (MSG-343 D5) — 인코딩·AI 경로가 모두 이 라이터를 지나므로 여기 한 곳에서만
	// 증가시켜 중복 계상을 막는다. 호출은 스테일 가드를 통과한 전이 적용 분기에만 둔다.
	private final VideoProcessingMetrics videoProcessingMetrics;
	private final VideoEncodingJobRepository encodingJobRepository;

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
	public boolean markEncoding(EncodingJobClaim claim) {
		Video video = videoRepository.findWithLockById(claim.videoId()).orElse(null);
		if (!isCurrentEncodingAttempt(video, claim.originalS3Key())) {
			logStaleSkip("인코딩 시작", claim.videoId(), claim.originalS3Key(), video);
			complete(claim);
			return false;
		}
		video.markEncoding();
		if (encodingJobRepository.verifyActive(claim) != 1) {
			throw new ClaimLostException(claim.jobId());
		}
		return true;
	}

	/**
	 * measuredDurationSec 은 인코더가 ffprobe 로 잰 길이(1~30 보정 완료, MSG-470)다. 스테일 가드에 걸려
	 * 전이가 skip 되면 길이 덮어쓰기도 함께 skip 된다 — 옛 파일의 실측값이 새 파일에 붙지 않는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markReady(Long videoId, String expectedOriginalKey, String encodedKey, String thumbnailKey,
		short measuredDurationSec) {
		if (!lockUploaderFirst(videoId)) {
			return;
		}
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentEncodingAttempt(video, expectedOriginalKey)) {
				logStaleSkip("인코딩 완료", videoId, expectedOriginalKey, video);
				return;
			}
			video.markReady(encodedKey, thumbnailKey, measuredDurationSec);
			recordOutcomeNotification(video, true);
			recordOutcome(video, true, VideoProcessingMetrics.PATH_ENCODING);
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markReady(EncodingJobClaim claim, String encodedKey, String thumbnailKey,
		short measuredDurationSec) {
		boolean uploaderLocked = lockUploaderFirst(claim.videoId());
		Video video = uploaderLocked ? videoRepository.findWithLockById(claim.videoId()).orElse(null) : null;
		boolean applied = isCurrentEncodingAttempt(video, claim.originalS3Key());
		if (applied) {
			video.markReady(encodedKey, thumbnailKey, measuredDurationSec);
			recordOutcomeNotification(video, true);
		}
		complete(claim);
		if (applied) {
			videoProcessingMetrics.recordOutcome(claim.enqueuedAt(), true, VideoProcessingMetrics.PATH_ENCODING);
		}
	}

	/**
	 * 블러 꺼짐 경로의 후행 하이라이트 저장 (MSG-456 D-1). READY 전이 후에 불리므로 가드도 READY 국면을
	 * 요구한다. 시도 식별 키는 originalS3Key 다. encoded 키는 결정적(videos/encoded/{userId}/{videoId}.mp4)
	 * 이라 교체 후 새 시도의 READY 와 구분되지 않지만, 원본 키는 교체마다 새 attemptUuid 키라 시도를 유일하게
	 * 식별한다 (isCurrentEncodingAttempt 와 같은 축). 불일치(교체로 UPLOADED 복귀, 삭제로 ACTIVE 이탈,
	 * 새 시도의 READY)면 logStaleSkip 후 버린다. lockUploaderFirst 는 타지 않는다. 알림도 users FK 쓰기도
	 * 없어 videos 행 잠금만으로 충분하다(잠금 순서 사이클 없음).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordHighlights(Long videoId, String expectedOriginalKey, List<List<Double>> highlights) {
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (video.getStatus() != VideoStatus.ACTIVE
				|| video.getProcessingStatus() != ProcessingStatus.READY
				|| !Objects.equals(video.getOriginalS3Key(), expectedOriginalKey)) {
				logStaleSkip("하이라이트 저장", videoId, expectedOriginalKey, video);
				return;
			}
			video.recordHighlights(highlights);
		});
	}

	/**
	 * AI 활성 인코딩 완료 지점 (MSG-149) — READY 대신 BLURRING. thumbnailUrl 은 폴러가 완료 시 기록한다(P2).
	 * measuredDurationSec 은 markReady 와 같은 실측 길이다 (MSG-470).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markEncoded(Long videoId, String expectedOriginalKey, String encodedKey,
		short measuredDurationSec) {
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentEncodingAttempt(video, expectedOriginalKey)) {
				// 스테일 markEncoded 는 blurringStartedAt 을 새로 찍어 폴러 가드를 정상 통과시키므로(MSG-241)
				// 여기서 못 막으면 옛 파일의 ai_job_id 가 새 시도에 붙는다.
				logStaleSkip("인코딩 완료(AI)", videoId, expectedOriginalKey, video);
				return;
			}
			video.markEncoded(encodedKey, measuredDurationSec);
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markEncoded(EncodingJobClaim claim, String encodedKey, short measuredDurationSec) {
		Video video = videoRepository.findWithLockById(claim.videoId()).orElse(null);
		if (isCurrentEncodingAttempt(video, claim.originalS3Key())) {
			video.markEncoded(encodedKey, measuredDurationSec);
		}
		complete(claim);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(EncodingJobClaim claim) {
		if (encodingJobRepository.complete(claim) != 1) {
			throw new ClaimLostException(claim.jobId());
		}
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
		if (!lockUploaderFirst(videoId)) {
			return false;
		}
		Video video = videoRepository.findWithLockById(videoId).orElse(null);
		if (!isCurrentBlurJob(video, expectedJobId)) {
			logStaleSkip("블러 결과", videoId, expectedJobId, video);
			return false;
		}
		video.applyBlurResult(blurredS3Key, highlights);
		video.markReadyFromBlurring(thumbnailKey);
		recordOutcomeNotification(video, true);
		recordOutcome(video, true, VideoProcessingMetrics.PATH_AI);
		return true;
	}

	/**
	 * AI 실패/유실/타임아웃을 FAILED 로 수렴 (MSG-150). markFailed 와 달리 폴러 전용이라 행 잠금 + 잡 정체성
	 * 가드를 건다 — 교체/삭제로 이미 대상이 아니게 된 영상을 옛 잡 판단으로 FAILED 로 밀지 않는다 (P1/P2).
	 * jobId 와 함께 blurringStartedAt(시도 넌스)도 확인한다 (P2-a) — 미제출 행(jobId null)끼리는 jobId 로
	 * 구분되지 않으므로 recordAiJob 과 대칭으로 시도 시작시각까지 일치해야 한다.
	 * failReason 은 프리체크 탈락 사유 코드(MSG-286), null = 시스템 오류 실패 — 가드 skip 시 사유도 안 남는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markBlurFailed(Long videoId, String expectedJobId, LocalDateTime expectedStartedAt,
		String failReason) {
		if (!lockUploaderFirst(videoId)) {
			return;
		}
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentBlurJob(video, expectedJobId)
				|| !Objects.equals(video.getBlurringStartedAt(), expectedStartedAt)) {
				logStaleSkip("블러 실패", videoId, expectedJobId, video);
				return;
			}
			video.markFailed(failReason);
			recordOutcomeNotification(video, false);
			recordOutcome(video, false, VideoProcessingMetrics.PATH_AI);
		});
	}

	/**
	 * 폴링 404(잡 유실)를 미제출로 복귀 (MSG-283) — aiJobId 만 null 로 되돌려 다음 주기의 미제출 경로가
	 * 재제출하게 한다. blurringStartedAt 은 유지한다 (Video.clearAiJob 주석 — 시도 넌스). expectedStartedAt 은
	 * 받지 않는다 — poll 경로에서만 호출돼 expectedJobId 가 항상 non-null 이라 jobId 일치만으로 시도가
	 * 유일하게 식별된다 (markBlurFailed 가 startedAt 까지 보는 이유인 "미제출 행끼리 jobId 구분 불가"가 없다).
	 * 교체는 replaceFile 이 aiJobId 를 비워 불일치로 skip, 삭제는 ACTIVE 이탈로 skip.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void clearAiJob(Long videoId, String expectedJobId) {
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentBlurJob(video, expectedJobId)) {
				logStaleSkip("잡 유실 복귀", videoId, expectedJobId, video);
				return;
			}
			video.clearAiJob();
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(Long videoId, String expectedOriginalKey) {
		if (!lockUploaderFirst(videoId)) {
			return;
		}
		videoRepository.findWithLockById(videoId).ifPresent(video -> {
			if (!isCurrentEncodingAttempt(video, expectedOriginalKey)) {
				// 교체 afterCommit 이 옛 원본을 S3 에서 지워 옛 태스크의 다운로드가 실패하는 게 대표 경로 —
				// 그 실패는 옛 시도의 것이니 새 시도의 UPLOADED/ENCODING 을 FAILED 로 밀지 않는다 (MSG-241).
				logStaleSkip("인코딩 실패", videoId, expectedOriginalKey, video);
				return;
			}
			video.markFailed();
			recordOutcomeNotification(video, false);
			recordOutcome(video, false, VideoProcessingMetrics.PATH_ENCODING);
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(EncodingJobClaim claim) {
		markClaimFailed(claim, false);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markDeadFailed(EncodingJobClaim claim) {
		markClaimFailed(claim, true);
	}

	private void markClaimFailed(EncodingJobClaim claim, boolean dead) {
		boolean uploaderLocked = lockUploaderFirst(claim.videoId());
		Video video = uploaderLocked ? videoRepository.findWithLockById(claim.videoId()).orElse(null) : null;
		boolean applied = isCurrentEncodingAttempt(video, claim.originalS3Key());
		if (applied) {
			video.markFailed();
			recordOutcomeNotification(video, false);
		}
		int updated = dead ? encodingJobRepository.completeDead(claim) : encodingJobRepository.complete(claim);
		if (updated != 1) {
			throw new ClaimLostException(claim.jobId());
		}
		if (applied) {
			videoProcessingMetrics.recordOutcome(claim.enqueuedAt(), false, VideoProcessingMetrics.PATH_ENCODING);
		}
	}

	private void recordOutcome(Video video, boolean ready, String path) {
		LocalDateTime enqueuedAt = encodingJobRepository
			.findEnqueuedAt(video.getId(), video.getOriginalS3Key())
			.orElse(null);
		videoProcessingMetrics.recordOutcome(enqueuedAt, ready, path);
	}

	/**
	 * 알림 배선 4개 전이(markReady·markBlurReady·markFailed·markBlurFailed) 전용 잠금 순서 선취 (Codex 2R) —
	 * record 의 FK 검사가 users 행 KEY SHARE 를 기다리므로, videos 행 잠금보다 먼저 users 를 잡아 탈퇴
	 * CASCADE(users 배타 잠금 → videos 행 삭제 대기)와 같은 users → videos 순서로 통일한다. 역순이면 교차
	 * 대기 사이클로 PostgreSQL 이 한쪽을 abort 시킨다. false = 영상 행 부재(탈퇴 CASCADE 완료) 또는 유저
	 * 부재(탈퇴 진행) — CASCADE 가 영상을 지우므로 전이·알림 모두 무의미해 조용히 스킵한다(스테일 가드와 같은 결).
	 */
	private boolean lockUploaderFirst(Long videoId) {
		Long userId = videoRepository.findUserIdById(videoId).orElse(null);   // 무잠금 선독 — user_id 불변
		if (userId == null) {
			return false;
		}
		return userRepository.findIdForKeyShare(userId).isPresent();
	}

	/**
	 * 처리 종결(READY·FAILED) 알림 outbox 기록 (MSG-313) — 전이 적용 직후에만 호출되므로 스테일 skip 시
	 * 알림도 함께 skip 된다. record 는 전파 REQUIRED 라 이 REQUIRES_NEW 전이 트랜잭션에 참여한다(원자적).
	 * event_key 의 시도 식별자는 originalS3Key 파일명 꼬리 — 교체마다 새 attemptUuid 키라 재처리 종결이
	 * 새 알림이 되고(FR-2), 같은 시도의 중복 발화는 (user_id, event_key) UNIQUE 의 DO NOTHING 이 흡수한다.
	 * 끝에서 45자로 자른다("마지막 '-' 뒤"는 UUID 내부 하이픈에 걸려 attemptUuid 가 조각나므로 금지).
	 */
	private void recordOutcomeNotification(Video video, boolean ready) {
		String originalKey = video.getOriginalS3Key();
		String fileName = originalKey.substring(originalKey.lastIndexOf('/') + 1);
		String suffix = fileName.length() <= EVENT_KEY_FILE_SUFFIX_MAX
			? fileName
			: fileName.substring(fileName.length() - EVENT_KEY_FILE_SUFFIX_MAX);
		String eventKey = "VIDEO:" + video.getId() + ":" + suffix;
		if (ready) {
			notificationCommandService.record(video.getUserId(), NotificationCategory.VIDEO, eventKey,
				"영상이 준비됐어요", "올린 영상 처리가 끝났어요. 지금 확인해 보세요");
		} else {
			notificationCommandService.record(video.getUserId(), NotificationCategory.VIDEO, eventKey,
				"영상 처리에 실패했어요", "올린 영상을 준비하지 못했어요. 영상을 다시 올려 주세요");
		}
	}

	/**
	 * 이 태스크가 인코딩한 원본과 현재 DB 행이 같은 시도인가 — 교체(키 갱신)/삭제(ACTIVE 이탈)로 벌어진 창을 닫는다.
	 * 처리 상태가 인코딩 국면(UPLOADED·ENCODING)일 것도 요구한다 (MSG-382) — 키가 같아도 이미 READY·FAILED·
	 * BLURRING 으로 넘어갔으면 "다른 시도"가 아니라 "끝난 시도"다. 같은 시도가 이중 트리거되면(확정 재요청 등)
	 * 늦게 끝난 쪽의 완료/실패가 이 조건 없이는 통과해 종결 지표가 한 번 더 오르고(NFR-OPS-08), markEncoding
	 * 재진입은 READY 를 ENCODING 으로 되돌리며, markEncoded 재적용은 블러 시도 넌스를 새로 찍는다.
	 */
	private boolean isCurrentEncodingAttempt(Video video, String expectedOriginalKey) {
		return video != null
			&& video.getStatus() == VideoStatus.ACTIVE
			&& (video.getProcessingStatus() == ProcessingStatus.UPLOADED
				|| video.getProcessingStatus() == ProcessingStatus.ENCODING)
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
