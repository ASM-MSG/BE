package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 폴러가 BLURRING 목록을 읽은 뒤 다운로드/업로드하는 사이 교체·삭제가 끼어드는 창(P1/P2)을 막는
 * 잡 정체성 가드를 검증한다. 가드는 행 잠금 조회(findWithLockById)로 fresh 엔티티를 읽으므로 목도 그 메서드로
 * 흉내낸다. @Transactional 은 단위 테스트에서 프록시가 없어 무시되지만 가드 로직 자체는 그대로 검증된다.
 */
@DisplayName("VideoStatusWriter — 블러 쓰기 잡 정체성 가드")
class VideoStatusWriterTest {

	private static final long VIDEO_ID = 7L;

	private VideoRepository videoRepository;
	private VideoStatusWriter statusWriter;

	@BeforeEach
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		statusWriter = new VideoStatusWriter(videoRepository);
	}

	/** BLURRING·ACTIVE 인, 아직 미제출(aiJobId=null) 시도의 영상. */
	private Video attempt() {
		Video video = Video.create(1L, "41716_110483", "videos/original/1/x.mp4",
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now());
		video.markEncoding();
		video.markEncoded("videos/encoded/1/7.mp4");   // BLURRING + blurringStartedAt=now (thumbnailUrl 은 null)
		ReflectionTestUtils.setField(video, "id", VIDEO_ID);
		return video;
	}

	/** 제출까지 끝나 job_id 가 붙은 시도의 영상. */
	private Video blurring(String jobId) {
		Video video = attempt();
		video.recordAiJob(jobId);
		return video;
	}

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
		video.replaceFile("videos/original/1/y.mp4", (short) 8);
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

		statusWriter.markBlurFailed(VIDEO_ID, "job-1", video.getBlurringStartedAt());

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.BLURRING);   // FAILED 로 밀리지 않음
	}

	@Test
	void 미제출_시도의_시작시각이_다르면_실패를_적용하지_않는다() {
		Video video = attempt();   // 현재 시도: BLURRING, aiJobId=null, blurringStartedAt=지금
		// 폴러가 본 옛 미제출 시도(교체 전)의 넌스 — jobId 는 둘 다 null 이라 startedAt 으로만 구분된다
		LocalDateTime oldStartedAt = video.getBlurringStartedAt().minusMinutes(40);
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.markBlurFailed(VIDEO_ID, null, oldStartedAt);

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.BLURRING);   // 새 시도는 FAILED 안 됨
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
		video.replaceFile("videos/original/1/y.mp4", (short) 8);
		given(videoRepository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		statusWriter.recordAiJob(VIDEO_ID, "job-1", oldStartedAt);

		assertThat(video.getAiJobId()).isNull();
	}
}
