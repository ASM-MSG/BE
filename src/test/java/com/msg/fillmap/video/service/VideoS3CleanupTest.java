package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.friend.service.FriendshipQueryService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * S3 미사용 객체 정리 — 어떤 키가 복사·삭제되는지 (MSG-133).
 *
 * 트랜잭션 없이 돌린다 — 정리는 afterCommit 에 등록되므로 @Transactional 테스트(항상 롤백)에서는
 * 영영 실행되지 않는다. VideoEncodingTriggerTest 가 같은 이유로 같은 방식을 쓴다.
 *
 * DB 동작(중복 검사·점령)은 VideoS3KeyValidationTest·VideoDeleteIntegrationTest 가 실 PostGIS 로 본다.
 */
@DisplayName("S3 정리 — 복사·삭제 키")
class VideoS3CleanupTest {

	private static final long USER_ID = 1L;
	private static final long VIDEO_ID = 7L;
	private static final String GRID_ID = "19495_9607";
	private static final String PENDING = "videos/pending/1/new.mp4";
	// original 키는 확정 시도마다 새 UUID 라(MSG-247 2R) 정확값 대신 파생 prefix 로 단언한다.
	private static final String ORIGINAL_CLAIM_PREFIX = "videos/original/1/new-";
	private static final String OLD_ORIGINAL = "videos/original/1/old.mp4";

	private VideoRepository repository;
	private S3Client s3Client;
	private VideoService service;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		logs = new ListAppender<>();
		logs.start();
		((Logger) LoggerFactory.getLogger(VideoServiceImpl.class)).addAppender(logs);

		repository = mock(VideoRepository.class);
		s3Client = mock(S3Client.class);
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willReturn(DeleteObjectsResponse.builder().build());
		// 확정의 실측 크기 검증(MSG-351 P1-1)이 headObject 응답을 읽는다 — 스텁이 없으면 null 로 NPE.
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		// 미션 판정 목 — record 반환 타입은 Mockito 기본값이 null 이라 EMPTY 를 명시한다 (MSG-223).
		MissionAwardService missionAwardService = mock(MissionAwardService.class);
		given(missionAwardService.awardOnUpload(org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString())).willReturn(MissionAwardResult.EMPTY);
		service = new VideoServiceImpl(repository, mock(VideoEncodingService.class), mock(VideoStatusWriter.class),
			mock(S3Presigner.class), s3Client,
			new AwsProperties("ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L)),
			mock(RegionStatsCommandService.class), mock(ThumbnailUrlPresigner.class), mock(BadgeAwardService.class),
			mock(StreakCommandService.class), missionAwardService, mock(HotScoreCommandService.class),
			mock(FriendshipQueryService.class), () -> new ZoneNameResolver(List.of()));
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(VideoServiceImpl.class)).detachAppender(logs);
	}

	/** 이미 확정돼 original 에 있는 영상. */
	private Video existingVideo() {
		Video video = Video.create(USER_ID, GRID_ID, OLD_ORIGINAL, null, (short) 10, LocalDateTime.now(ZoneOffset.UTC),
			Visibility.PRIVATE);
		ReflectionTestUtils.setField(video, "id", VIDEO_ID);
		return video;
	}

	private List<String> deletedKeys() {
		ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
		then(s3Client).should().deleteObjects(captor.capture());
		return captor.getValue().delete().objects().stream().map(ObjectIdentifier::key).toList();
	}

	@Test
	@DisplayName("확정하면 pending 을 original 로 복사하고 DB 에는 original 키를 남긴다")
	void 확정하면_original_로_복사된다() {
		Video saved = existingVideo();
		// 확정은 클레임 선행 직렬화로 saveAndFlush 를 쓴다 (MSG-247 P1) — 스텁도 따라간다.
		given(repository.saveAndFlush(any(Video.class))).willReturn(saved);

		service.saveVideo(USER_ID, new VideoUploadRequestDto(PENDING, 37.5445, 127.0560, (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), "PRIVATE"));

		ArgumentCaptor<CopyObjectRequest> copy = ArgumentCaptor.forClass(CopyObjectRequest.class);
		then(s3Client).should().copyObject(copy.capture());
		assertThat(copy.getValue().sourceKey()).as("원본은 pending").isEqualTo(PENDING);
		assertThat(copy.getValue().destinationKey())
			.as("사본은 original — pendingStem 을 보존한 시도별 키 (MSG-247 2R)")
			.startsWith(ORIGINAL_CLAIM_PREFIX).endsWith(".mp4");

		ArgumentCaptor<Video> persisted = ArgumentCaptor.forClass(Video.class);
		then(repository).should().saveAndFlush(persisted.capture());
		assertThat(persisted.getValue().getOriginalS3Key())
			.as("DB 에는 클라이언트가 준 pending 이 아니라 복사된 그 original 키가 남아야 한다")
			.isEqualTo(copy.getValue().destinationKey());
	}

	@Test
	@DisplayName("확정해도 pending 은 지우지 않는다 — 라이프사이클이 쓸어간다")
	void 확정해도_pending_은_안_지운다() {
		given(repository.saveAndFlush(any(Video.class))).willReturn(existingVideo());

		service.saveVideo(USER_ID, new VideoUploadRequestDto(PENDING, 37.5445, 127.0560, (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), "PRIVATE"));

		// 지우면 API 호출 하나와 그 실패 경로가 늘 뿐, 만료 규칙이 어차피 정리한다.
		then(s3Client).should(never()).deleteObjects(any(DeleteObjectsRequest.class));
	}

	// 검증: FR-VIDEO-11
	@Test
	@DisplayName("삭제하면 원본·인코딩본·썸네일을 실제로 지운다 — MSG-72 D2 정정")
	void 삭제하면_S3_에서도_지운다() {
		Video video = existingVideo();
		video.markReady("videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");
		// 삭제는 잠금 로드다 (MSG-243) — 스텁도 프로덕션 로드 메서드를 따라간다.
		given(repository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		service.deleteVideo(USER_ID, VIDEO_ID);

		assertThat(deletedKeys()).containsExactlyInAnyOrder(
			OLD_ORIGINAL, "videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");
	}

	// 검증: FR-VIDEO-11
	@Test
	@DisplayName("삭제하면 블러본도 지운다 (MSG-145)")
	void 삭제하면_블러본도_지운다() {
		Video video = existingVideo();
		video.markReady("videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");
		video.applyBlurResult("videos/blurred/1/7.mp4", List.of(List.of(0.0, 3.33)));
		given(repository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(video));

		service.deleteVideo(USER_ID, VIDEO_ID);

		assertThat(deletedKeys()).contains("videos/blurred/1/7.mp4");
	}

	// 검증: FR-VIDEO-11
	@Test
	@DisplayName("인코딩 전 영상을 지우면 원본만 지운다 — null 키를 넣으면 S3 가 400 을 낸다")
	void 인코딩_전_삭제는_원본만_지운다() {
		given(repository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(existingVideo()));

		service.deleteVideo(USER_ID, VIDEO_ID);

		assertThat(deletedKeys()).containsExactly(OLD_ORIGINAL);
	}

	@Test
	@DisplayName("배치 삭제가 조용히 실패하면 로그로 드러난다 — 예외가 안 오는 API 다")
	void 배치_삭제_실패는_응답으로_온다() {
		// DeleteObjects 는 권한이 없어도 예외를 던지지 않는다. HTTP 200 + errors 로 답한다.
		// 응답을 안 보면 IAM 에 s3:DeleteObject 가 빠진 채 배포돼도 아무도 모른다 (실제로 겪음).
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).willReturn(
			DeleteObjectsResponse.builder()
				.errors(S3Error.builder().key(OLD_ORIGINAL).code("AccessDenied").build())
				.build());
		given(repository.findWithLockById(VIDEO_ID)).willReturn(Optional.of(existingVideo()));

		// 커밋 후 정리라 실패해도 사용자 요청은 성공해야 한다 — 삭제는 이미 커밋됐다.
		assertThatCode(() -> service.deleteVideo(USER_ID, VIDEO_ID)).doesNotThrowAnyException();

		assertThat(logs.list)
			.filteredOn(e -> e.getLevel() == Level.ERROR)
			.as("조용히 실패하면 안 된다")
			.isNotEmpty();
	}

	@Test
	@DisplayName("교체하면 옛 원본만 지운다 — 인코딩본은 videoId 키라 덮어쓰기된다")
	void 교체하면_옛_원본만_지운다() {
		Video video = existingVideo();
		video.markReady("videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");
		given(repository.findById(VIDEO_ID)).willReturn(Optional.of(video));

		service.replaceVideo(USER_ID, VIDEO_ID, new VideoReplaceRequestDto(
			PENDING, null, null, (short) 7, LocalDateTime.now(ZoneOffset.UTC)));

		// 새 original 을 지우면 방금 올린 파일이 사라지고, 인코딩본을 지우면 재인코딩이 어차피 덮어쓸
		// 자리를 헛되이 건드린다.
		assertThat(deletedKeys()).containsExactly(OLD_ORIGINAL);
	}

	@Test
	@DisplayName("교체하면 옛 블러본도 지운다 (MSG-145)")
	void 교체하면_옛_블러본도_지운다() {
		Video video = existingVideo();
		video.markReady("videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");
		video.applyBlurResult("videos/blurred/1/7.mp4", List.of(List.of(0.0, 3.33)));
		given(repository.findById(VIDEO_ID)).willReturn(Optional.of(video));

		service.replaceVideo(USER_ID, VIDEO_ID, new VideoReplaceRequestDto(
			PENDING, null, null, (short) 7, LocalDateTime.now(ZoneOffset.UTC)));

		// replaceFile 이 블러본 키를 null 로 밀기 전에 잡아둔 값이어야 한다 — 캡처 순서 회귀 방지.
		assertThat(deletedKeys()).containsExactlyInAnyOrder(OLD_ORIGINAL, "videos/blurred/1/7.mp4");
	}
}
