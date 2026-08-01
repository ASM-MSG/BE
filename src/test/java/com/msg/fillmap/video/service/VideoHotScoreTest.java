package com.msg.fillmap.video.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 업로드 훅의 핫스코어 배선 (MSG-183). 트랜잭션 없이 돌리므로 afterCommit 폴백이 즉시 실행돼
 * mock 호출 유무로 배선을 검증한다 (VideoEncodingTriggerTest 방식). 증분·TTL·실패 비전파는 스토어
 * 몫 — HotScoreCommandServiceImplTest 가 실 Redis 로 본다.
 */
@DisplayName("핫스코어 훅 — 업로드 확정 배선")
class VideoHotScoreTest {

	private static final long USER_ID = 1L;
	private static final long VIDEO_ID = 7L;
	// 37.5445, 127.0560 의 격자 — VideoEncodingTriggerTest 와 동일 좌표.
	private static final String GRID_ID = "41716_110483";

	private VideoRepository repository;
	private HotScoreCommandService hotScoreCommandService;
	private VideoService service;

	@BeforeEach
	void setUp() {
		repository = mock(VideoRepository.class);
		hotScoreCommandService = mock(HotScoreCommandService.class);

		S3Client s3Client = mock(S3Client.class);
		// 교체·삭제의 afterCommit 정리(deleteQuietly)가 응답을 읽는다 — 스텁이 없으면 null 로 NPE.
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willReturn(DeleteObjectsResponse.builder().build());

		// 미션 판정 목 — record 반환 타입은 Mockito 기본값이 null 이라 EMPTY 를 명시한다 (MSG-223).
		MissionAwardService missionAwardService = mock(MissionAwardService.class);
		given(missionAwardService.awardOnUpload(anyLong(), anyString())).willReturn(MissionAwardResult.EMPTY);

		service = new VideoServiceImpl(repository, mock(VideoEncodingService.class), mock(VideoStatusWriter.class),
			mock(S3Presigner.class), s3Client,
			new AwsProperties("ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L)),
			mock(RegionStatsCommandService.class), mock(ThumbnailUrlPresigner.class), mock(BadgeAwardService.class),
			mock(StreakCommandService.class), missionAwardService, hotScoreCommandService);
	}

	private void givenSavedVideo() {
		Video saved = Video.create(USER_ID, GRID_ID, "videos/original/1/x.mp4", null, (short) 10,
			LocalDateTime.now());
		ReflectionTestUtils.setField(saved, "id", VIDEO_ID);
		given(repository.saveAndFlush(any(Video.class))).willReturn(saved);
	}

	private VideoUploadRequestDto uploadRequest() {
		return new VideoUploadRequestDto("videos/pending/1/x.mp4", 37.5445, 127.0560, (short) 10, LocalDateTime.now());
	}

	/** 이미 확정돼 original 에 있는 영상 — 교체·삭제 대상. */
	private Video existingVideo() {
		Video video = Video.create(USER_ID, GRID_ID, "videos/original/1/old.mp4", null, (short) 10,
			LocalDateTime.now());
		ReflectionTestUtils.setField(video, "id", VIDEO_ID);
		return video;
	}

	@Test
	void 업로드가_확정되면_recordUpload가_그_격자ID로_호출된다() {
		givenSavedVideo();

		service.saveVideo(USER_ID, uploadRequest());

		verify(hotScoreCommandService).recordUpload(GRID_ID);
	}

	@Test
	void 재방문_업로드도_recordUpload가_호출된다() {
		givenSavedVideo();
		// 신호는 방문(업로드 이벤트)이라 재방문도 전부 인정 (D1) — 훅이 점령 분기 바깥임을 고정한다.
		given(repository.existsUserGrid(USER_ID, GRID_ID)).willReturn(true);

		service.saveVideo(USER_ID, uploadRequest());

		verify(hotScoreCommandService).recordUpload(GRID_ID);
	}

	@Test
	void 영상_교체는_recordUpload를_호출하지_않는다() {
		// 교체는 새 방문이 아니라 파일 교체다 (D6) — 스트릭과 동일 판단.
		given(repository.findById(VIDEO_ID)).willReturn(Optional.of(existingVideo()));

		service.replaceVideo(USER_ID, VIDEO_ID,
			new VideoReplaceRequestDto("videos/pending/1/new.mp4", null, null, (short) 7, LocalDateTime.now()));

		verifyNoInteractions(hotScoreCommandService);
	}

	@Test
	void 영상_삭제는_recordUpload를_호출하지_않는다() {
		// 삭제 차감 없음 (D4) — 신호는 윈도우 만료로 자연 소멸한다.
		given(repository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(existingVideo()));

		service.deleteVideo(USER_ID, VIDEO_ID);

		verifyNoInteractions(hotScoreCommandService);
	}
}
