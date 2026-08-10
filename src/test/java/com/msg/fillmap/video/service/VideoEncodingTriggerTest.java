package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.friend.service.FriendshipQueryService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameResolver;

import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 인코딩 큐가 가득 찼을 때의 업로드 동작.
 * executor 는 @Async 메서드 본문이 아니라 submit 을 호출한 스레드에 TaskRejectedException 을 던지므로,
 * 이를 삼키지 않으면 이미 커밋된 업로드가 500 으로 응답된다.
 */
@DisplayName("인코딩 트리거 — 큐 포화")
class VideoEncodingTriggerTest {

	private static final long USER_ID = 1L;

	@Test
	void 큐가_포화여도_업로드는_성공하고_FAILED_로_기록된다() {
		VideoRepository repository = mock(VideoRepository.class);
		VideoEncodingService encodingService = mock(VideoEncodingService.class);
		VideoStatusWriter statusWriter = mock(VideoStatusWriter.class);

		Video saved = Video.create(USER_ID, "19495_9607", "videos/original/1/x.mp4", null, (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), Visibility.PRIVATE);
		org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 7L);
		// 확정은 클레임 선행 직렬화로 saveAndFlush 를 쓴다 (MSG-247 P1) — 스텁도 따라간다.
		org.mockito.BDDMockito.given(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(Video.class)))
			.willReturn(saved);

		// 큐 포화 상황 재현
		willThrow(new TaskRejectedException("queue full")).given(encodingService).encode(anyLong(), anyString());

		// 미션 판정 목 — record 반환 타입은 Mockito 기본값이 null 이라 EMPTY 를 명시한다 (MSG-223).
		MissionAwardService missionAwardService = mock(MissionAwardService.class);
		org.mockito.BDDMockito.given(missionAwardService.awardOnUpload(anyLong(), anyString()))
			.willReturn(MissionAwardResult.EMPTY);

		// S3Client 목은 headObject 에서 예외를 던지지 않는다 = 객체가 존재하는 정상 업로드.
		VideoService service = new VideoServiceImpl(repository, encodingService, statusWriter,
			mock(S3Presigner.class), mock(software.amazon.awssdk.services.s3.S3Client.class),
			new AwsProperties("ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L)),
			mock(RegionStatsCommandService.class), mock(ThumbnailUrlPresigner.class), mock(BadgeAwardService.class),
			mock(StreakCommandService.class), missionAwardService, mock(HotScoreCommandService.class),
			mock(FriendshipQueryService.class), () -> new ZoneNameResolver(List.of()));

		VideoUploadRequestDto request = new VideoUploadRequestDto(
			"videos/pending/1/x.mp4", 37.5445, 127.0560, (short) 10, LocalDateTime.now(ZoneOffset.UTC), "PRIVATE");

		// 업로드는 이미 커밋된 상태다 — 여기서 예외가 나가면 클라이언트가 재시도해 중복 업로드가 된다.
		assertThatCode(() -> service.saveVideo(USER_ID, request)).doesNotThrowAnyException();

		// 이 경로도 현재 시도의 원본 키를 안다 — 가드 라이터 시그니처를 그대로 따른다 (MSG-241).
		// 키는 시도별 발급이라(MSG-247 2R) 정확값 대신 pendingStem 파생 prefix 로 검증한다.
		verify(statusWriter).markFailed(
			org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.startsWith("videos/original/1/x-"));
	}
}
