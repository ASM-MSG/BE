package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 폴러가 BLURRING 목록을 읽은 뒤 다운로드/업로드하는 사이 교체·삭제가 끼어드는 창(P1/P2)을 막는
 * 잡 정체성 가드와, 인코딩 태스크의 쓰기가 교체·삭제 이후 새 시도를 덮는 창을 막는 원본 키 가드(MSG-241)를
 * 검증한다. 가드는 행 잠금 조회(findWithLockById)로 fresh 엔티티를 읽으므로 목도 그 메서드로
 * 흉내낸다. @Transactional 은 단위 테스트에서 프록시가 없어 무시되지만 가드 로직 자체는 그대로 검증된다.
 */
@DisplayName("VideoStatusWriter — 블러·인코딩 쓰기 정체성 가드")
class VideoStatusWriterTest {

	private static final long VIDEO_ID = 7L;
	private static final String K1 = "videos/original/1/x.mp4";
	private static final String K2 = "videos/original/1/y.mp4";

	private VideoRepository videoRepository;
	private VideoProcessingMetrics videoProcessingMetrics;
	private VideoStatusWriter statusWriter;

	@BeforeEach
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		videoProcessingMetrics = mock(VideoProcessingMetrics.class);
		UserRepository userRepository = mock(UserRepository.class);
		// 알림 배선 전이의 users 선취(잠금 순서 통일)가 통과하도록 스텁 — 순서 자체는 통합·리뷰 검증 몫.
		given(videoRepository.findUserIdById(VIDEO_ID)).willReturn(Optional.of(1L));
		given(userRepository.findIdForKeyShare(1L)).willReturn(Optional.of(1L));
		statusWriter = new VideoStatusWriter(videoRepository, userRepository,
			mock(NotificationCommandService.class), videoProcessingMetrics);
	}

	/** BLURRING·ACTIVE 인, 아직 미제출(aiJobId=null) 시도의 영상. */
	private Video attempt() {
		Video video = Video.create(1L, "19495_9607", "videos/original/1/x.mp4",
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now(), Visibility.PRIVATE);
		video.markEncoding();
		// BLURRING + blurringStartedAt=now (thumbnailUrl 은 null). 길이는 신고값 그대로 둬 이 픽스처의 다른 검증에 영향 없다.
		video.markEncoded("videos/encoded/1/7.mp4", video.getDurationSec());
		ReflectionTestUtils.setField(video, "id", VIDEO_ID);
		return video;
	}

	/** 제출까지 끝나 job_id 가 붙은 시도의 영상. */
	private Video blurring(String jobId) {
		Video video = attempt();
		video.recordAiJob(jobId);
		return video;
	}

	// 검증: FR-MEDIA-02
	@Test
	void 현재_잡이면_블러본을_적용하고_READY로_전이한다() {
		Video video = blurring("job-1");
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		boolean applied = statusWriter.markBlurReady(VIDEO_ID, "job-1", "videos/blurred/1/7.mp4",
			"videos/thumb/1/7.jpg", List.of(List.of(0.0, 3.33)));

		assertThat(applied).isTrue();
		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
		assertThat(video.getBlurredS3Key()).isEqualTo("videos/blurred/1/7.mp4");
		assertThat(video.getThumbnailUrl()).isEqualTo("videos/thumb/1/7.jpg");   // 업로드 완료 후 키 기록(R5)
	}

	@Test
	void 교체로_잡이_바뀌면_옛_잡_결과를_적용하지_않는다() {
		Video video = blurring("job-1");
		// 폴러가 다운로드/업로드하는 사이 사용자가 교체 → replaceFile 이 AI 필드를 비우고 UPLOADED 로 되돌림
		video.replaceFile("videos/original/1/y.mp4", (short) 8, LocalDateTime.now());
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		boolean applied = statusWriter.markBlurReady(VIDEO_ID, "job-1", "videos/blurred/1/7.mp4",
			"videos/thumb/1/7.jpg", List.of(List.of(0.0, 3.33)));

		assertThat(applied).isFalse();
		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADED);
		assertThat(video.getBlurredS3Key()).isNull();
	}

	@Test
	void 삭제된_영상이면_옛_잡_실패를_적용하지_않는다() {
		Video video = blurring("job-1");
		video.markDeleted();   // status=DELETED, processing_status 는 BLURRING 유지
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markBlurFailed(VIDEO_ID, "job-1", video.getBlurringStartedAt(), null);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.BLURRING);   // FAILED 로 밀리지 않음
	}

	@Test
	void 미제출_시도의_시작시각이_다르면_실패를_적용하지_않는다() {
		Video video = attempt();   // 현재 시도: BLURRING, aiJobId=null, blurringStartedAt=지금
		// 폴러가 본 옛 미제출 시도(교체 전)의 넌스 — jobId 는 둘 다 null 이라 startedAt 으로만 구분된다
		LocalDateTime oldStartedAt = video.getBlurringStartedAt().minusMinutes(40);
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markBlurFailed(VIDEO_ID, null, oldStartedAt, null);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.BLURRING);   // 새 시도는 FAILED 안 됨
	}

	// ── 프리체크 탈락 사유 기록 (MSG-286) ──

	// 검증: FR-MEDIA-07
	@Test
	void 사유와_함께_실패하면_FAILED와_사유_코드가_기록된다() {
		Video video = blurring("job-1");
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markBlurFailed(VIDEO_ID, "job-1", video.getBlurringStartedAt(), "too_dark");

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
		assertThat(video.getFailReason()).isEqualTo("too_dark");
	}

	// 검증: FR-MEDIA-07
	@Test
	void 사유_없이_실패하면_failReason은_null이다() {
		Video video = blurring("job-1");
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markBlurFailed(VIDEO_ID, "job-1", video.getBlurringStartedAt(), null);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
		assertThat(video.getFailReason()).isNull();   // NULL = 시스템 오류 실패 (FR-4 구분)
	}

	@Test
	void 가드가_거부하면_사유도_기록되지_않는다() {
		Video video = blurring("job-1");
		LocalDateTime oldStartedAt = video.getBlurringStartedAt();
		// 폴러가 탈락을 보고 실패 처리하러 오는 사이 사용자가 교체 → 대상이 아니게 된 영상에 옛 잡 사유를 밀지 않는다
		video.replaceFile(K2, (short) 8, LocalDateTime.now());
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markBlurFailed(VIDEO_ID, "job-1", oldStartedAt, "too_dark");

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADED);   // skip — 새 파일 흐름 유지
		assertThat(video.getFailReason()).isNull();
	}

	@Test
	void 현재_시도면_잡ID를_기록한다() {
		Video video = attempt();
		LocalDateTime startedAt = video.getBlurringStartedAt();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.recordAiJob(VIDEO_ID, "job-1", startedAt);

		assertThat(video.getAiJobId()).isEqualTo("job-1");
	}

	@Test
	void 교체로_시도가_바뀌면_옛_파일의_잡ID를_기록하지_않는다() {
		Video video = attempt();
		LocalDateTime oldStartedAt = video.getBlurringStartedAt();   // 폴러가 목록 로드 시점에 잡은 시도 넌스
		// 제출 왕복 사이 사용자가 교체 → replaceFile 이 blurringStartedAt·aiJobId 를 비우고 UPLOADED 로 되돌림
		video.replaceFile("videos/original/1/y.mp4", (short) 8, LocalDateTime.now());
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.recordAiJob(VIDEO_ID, "job-1", oldStartedAt);

		assertThat(video.getAiJobId()).isNull();
	}

	// ── 잡 유실(404) 미제출 복귀 가드 (MSG-283) ──

	@Test
	void 현재_잡이면_aiJobId만_null로_되돌리고_blurringStartedAt은_유지한다() {
		Video video = blurring("job-1");
		LocalDateTime startedAt = video.getBlurringStartedAt();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.clearAiJob(VIDEO_ID, "job-1");

		assertThat(video.getAiJobId()).isNull();
		assertThat(video.getBlurringStartedAt()).isEqualTo(startedAt);   // 시도 넌스 유지 — 타임아웃 창 리셋 금지 (D2)
		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.BLURRING);   // 미제출 경로 재진입 대상
	}

	@Test
	void 교체로_잡이_바뀌면_옛_잡의_clear를_적용하지_않는다() {
		Video video = blurring("job-1");
		// 폴러가 404 를 보고 clear 하러 오는 사이 사용자가 교체 → replaceFile 이 AI 필드를 비우고 UPLOADED 로 되돌림
		video.replaceFile(K2, (short) 8, LocalDateTime.now());
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.clearAiJob(VIDEO_ID, "job-1");

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADED);   // jobId 불일치로 skip — 새 파일 흐름 유지
	}

	@Test
	void 삭제된_영상이면_옛_잡의_clear를_적용하지_않는다() {
		Video video = blurring("job-1");
		video.markDeleted();   // status=DELETED, aiJobId 는 그대로 — ACTIVE 가드가 걸러야 한다
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.clearAiJob(VIDEO_ID, "job-1");

		assertThat(video.getAiJobId()).isEqualTo("job-1");   // clear 안 됨 — 삭제본을 미제출로 되살리지 않는다
	}

	// ── 인코딩 라이터 원본 키 가드 (MSG-241) ──

	/** K1 원본으로 ENCODING 중인 시도의 영상. */
	private Video encoding() {
		Video video = Video.create(1L, "19495_9607", K1,
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now(), Visibility.PRIVATE);
		video.markEncoding();
		ReflectionTestUtils.setField(video, "id", VIDEO_ID);
		return video;
	}

	/** K1 인코딩 태스크가 도는 사이 사용자가 교체한 영상 — 키 K2·UPLOADED 로 리셋됨. */
	private Video replacedWhileEncoding() {
		Video video = encoding();
		video.replaceFile(K2, (short) 8, LocalDateTime.now());
		return video;
	}

	@Test
	void 교체로_원본이_바뀌면_옛_인코딩_완료를_적용하지_않는다() {
		Video video = replacedWhileEncoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg", (short) 10);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADED);   // 새 파일 흐름 유지
		assertThat(video.getEncodedUrl()).isNull();
		assertThat(video.getThumbnailUrl()).isNull();
	}

	@Test
	void 교체로_원본이_바뀌면_옛_완료가_BLURRING_으로_전이시키지_못한다() {
		Video video = replacedWhileEncoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markEncoded(VIDEO_ID, K1, "videos/encoded/1/7.mp4", (short) 10);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADED);
		// blurringStartedAt 이 안 찍히면 폴러 제출 대상이 아니다 — 옛 파일의 ai_job_id 잔존 경로 차단
		assertThat(video.getBlurringStartedAt()).isNull();
	}

	@Test
	void 교체로_원본이_바뀌면_옛_인코딩_실패가_FAILED_로_밀지_못한다() {
		Video video = replacedWhileEncoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markFailed(VIDEO_ID, K1);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADED);
	}

	@Test
	void 옛_시도의_markEncoding_이_새_시도_상태를_되돌리지_못한다() {
		// 새 시도(K2)가 이미 READY 까지 갔는데, 큐에 밀려 있던 옛 태스크(K1)가 뒤늦게 시작을 알린다
		Video video = replacedWhileEncoding();
		video.markEncoding();
		video.markReady("videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg", video.getDurationSec());
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		boolean applied = statusWriter.markEncoding(VIDEO_ID, K1);

		assertThat(applied).isFalse();
		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
	}

	@Test
	void 삭제된_영상에는_인코딩_상태를_쓰지_않는다() {
		Video video = encoding();
		video.markDeleted();   // status=DELETED, 키는 K1 그대로 — ACTIVE 가드가 걸러야 한다
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg", (short) 10);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.ENCODING);   // 불변
		assertThat(video.getEncodedUrl()).isNull();
	}

	@Test
	void 현재_시도의_완료는_그대로_적용된다() {
		Video video = encoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg", (short) 10);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
		assertThat(video.getEncodedUrl()).isEqualTo("videos/encoded/1/7.mp4");
		assertThat(video.getThumbnailUrl()).isEqualTo("videos/thumb/1/7.jpg");
		verify(videoProcessingMetrics).recordOutcome(VIDEO_ID, true, VideoProcessingMetrics.PATH_ENCODING);
	}

	// ── 실측 길이 반영 (MSG-470) ──
	// 스테일 가드에 걸리면 상태와 함께 길이도 skip 된다 — 옛 파일의 실측값이 새 파일에 붙으면 안 된다.

	// 검증: FR-MEDIA-19
	@Test
	void 현재_시도면_markReady가_실측_길이를_반영한다() {
		Video video = encoding();   // 신고값 10 초로 확정된 영상
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg", (short) 13);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
		assertThat(video.getDurationSec()).isEqualTo((short) 13);
	}

	// 검증: FR-MEDIA-19
	@Test
	void 스테일_markReady는_길이도_덮어쓰지_않는다() {
		Video video = replacedWhileEncoding();   // 교체로 신고값 8 초인 새 파일
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg", (short) 13);

		assertThat(video.getDurationSec()).isEqualTo((short) 8);   // 옛 파일 실측값이 새 파일에 붙지 않는다
	}

	// 검증: FR-MEDIA-19
	@Test
	void 스테일_markEncoded는_길이도_덮어쓰지_않는다() {
		Video video = replacedWhileEncoding();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markEncoded(VIDEO_ID, K1, "videos/encoded/1/7.mp4", (short) 13);

		assertThat(video.getDurationSec()).isEqualTo((short) 8);
	}

	// ── 종결 후 중복 종결 가드 (MSG-382, NFR-OPS-08) ──
	// 같은 시도가 이중 트리거되면 늦게 끝난 태스크의 종결이 키 일치만으로는 통과했다 —
	// 인코딩 국면(UPLOADED·ENCODING) 조건이 이를 막아 종결 지표가 시도당 1회만 오른다.

	/** K1 시도가 이미 READY 로 끝난 영상. */
	private Video ready() {
		Video video = encoding();
		video.markReady("videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg", video.getDurationSec());
		return video;
	}

	@Test
	void 이미_READY_면_중복_완료가_적용되지_않고_종결_지표도_오르지_않는다() {
		Video video = ready();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markReady(VIDEO_ID, K1, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg", (short) 10);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
		verify(videoProcessingMetrics, never()).recordOutcome(anyLong(), anyBoolean(), anyString());
	}

	@Test
	void 이미_READY_면_늦게_온_실패가_FAILED_로_밀지_못한다() {
		Video video = ready();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markFailed(VIDEO_ID, K1);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
		verify(videoProcessingMetrics, never()).recordOutcome(anyLong(), anyBoolean(), anyString());
	}

	@Test
	void 이미_FAILED_면_중복_실패가_종결_지표를_다시_올리지_않는다() {
		Video video = encoding();
		video.markFailed();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markFailed(VIDEO_ID, K1);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
		verify(videoProcessingMetrics, never()).recordOutcome(anyLong(), anyBoolean(), anyString());
	}

	@Test
	void 이미_READY_면_중복_인코딩_시작이_상태를_되돌리지_못한다() {
		Video video = ready();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		boolean applied = statusWriter.markEncoding(VIDEO_ID, K1);

		assertThat(applied).isFalse();
		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
	}

	// ── 후행 하이라이트 저장 가드 (MSG-456 D-1) ──
	// 워커가 READY 전이 뒤에 돌므로 가드도 READY 국면 + 원본 키 일치를 요구한다 — encoded 키는 결정적이라
	// 교체 후 새 시도의 READY 와 구분되지 않지만 원본 키는 교체마다 새 attemptUuid 키라 시도를 유일 식별한다.

	// 검증: FR-MEDIA-18
	@Test
	void READY_행에_같은_원본_키면_하이라이트가_저장된다() {
		Video video = ready();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.recordHighlights(VIDEO_ID, K1, List.of(List.of(0.0, 3.33)));

		assertThat(video.getHighlights()).isEqualTo(List.of(List.of(0.0, 3.33)));
	}

	@Test
	void 교체로_원본_키가_바뀐_행에는_하이라이트를_저장하지_않는다() {
		// 옛 시도(K1)의 워커가 도는 사이 교체 → 새 시도(K2)가 이미 READY 까지 감 — encoded 키가 같아도
		// 원본 키 불일치로 옛 하이라이트가 새 시도에 붙지 않는다
		Video video = ready();
		video.replaceFile(K2, (short) 8, LocalDateTime.now());
		video.markEncoding();
		video.markReady("videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg", video.getDurationSec());
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.recordHighlights(VIDEO_ID, K1, List.of(List.of(0.0, 3.33)));

		assertThat(video.getHighlights()).isNull();
	}

	@Test
	void READY가_아닌_행에는_하이라이트를_저장하지_않는다() {
		// 워커가 도는 사이 교체로 UPLOADED 복귀한 창 — READY 국면 요구가 거른다
		Video video = ready();
		video.replaceFile(K2, (short) 8, LocalDateTime.now());
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.recordHighlights(VIDEO_ID, K1, List.of(List.of(0.0, 3.33)));

		assertThat(video.getHighlights()).isNull();
		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADED);   // 새 파일 흐름 유지
	}

	@Test
	void 블러_국면으로_넘어간_뒤_중복_인코딩_완료가_블러_시도를_다시_시작하지_못한다() {
		Video video = attempt();   // K1 시도가 markEncoded 로 BLURRING + 넌스가 찍힌 상태
		LocalDateTime nonce = video.getBlurringStartedAt();
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markEncoded(VIDEO_ID, K1, "videos/encoded/1/7.mp4", (short) 10);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.BLURRING);
		assertThat(video.getBlurringStartedAt()).isEqualTo(nonce);   // 넌스 재발급 없음 — 폴러 가드가 안 흔들린다
	}
}
