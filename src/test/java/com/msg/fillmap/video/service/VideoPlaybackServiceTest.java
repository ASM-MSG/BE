package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.VideoPlaybackResponseDto;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 단건 재생 조회 접근 제어 매트릭스(MSG-206 §접근 제어 매트릭스) 를 서비스 단위로 커버한다. presign 은
 * 네트워크 없는 로컬 서명이라 더미 자격증명으로 실제 S3Presigner 를 써서 URL 발급 여부·재생 소스 key 를
 * 검증한다(VideoGridQueryServiceTest 동일 전략). 조회수 증가는 리포지토리 mock 호출 유무로 판정한다.
 */
@DisplayName("VideoService 단건 재생 조회 (접근 제어 매트릭스)")
class VideoPlaybackServiceTest {

	private static final Long OWNER_ID = 42L;
	private static final Long OTHER_ID = 99L;
	private static final Long VIDEO_ID = 1042L;
	private static final String GRID_ID = "41642_110458";
	private static final String ENCODED_KEY = "videos/encoded/1042.mp4";
	private static final String BLURRED_KEY = "videos/blurred/1042.mp4";
	private static final String THUMB_KEY = "videos/thumb/1042.jpg";
	private static final LocalDateTime RECORDED_AT = LocalDateTime.of(2026, 7, 20, 18, 3, 11);

	private VideoRepository videoRepository;
	private ThumbnailUrlPresigner thumbnailUrlPresigner;
	private VideoService videoService;

	@BeforeEach
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		S3Presigner presigner = S3Presigner.builder()
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
			.build();
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L));
		thumbnailUrlPresigner = new ThumbnailUrlPresigner(presigner, properties);

		videoService = new VideoServiceImpl(
			videoRepository, mock(VideoEncodingService.class), mock(VideoStatusWriter.class),
			presigner, mock(S3Client.class), properties,
			mock(RegionStatsCommandService.class), thumbnailUrlPresigner, mock(BadgeAwardService.class),
			mock(StreakCommandService.class), mock(MissionAwardService.class));
	}

	/** 모든 상태 축을 명시 지정하는 코어 빌더 — 엔티티에 세터가 없어 리플렉션으로 벌린다. */
	private Video video(VideoStatus status, Visibility visibility, ProcessingStatus processingStatus,
		String encodedKey, String blurredKey, String thumbKey, long viewCount) {
		Video v = Video.create(OWNER_ID, GRID_ID, "videos/original/x.mp4", null, (short) 12, RECORDED_AT,
			Visibility.PRIVATE);
		ReflectionTestUtils.setField(v, "id", VIDEO_ID);
		ReflectionTestUtils.setField(v, "status", status);
		ReflectionTestUtils.setField(v, "visibility", visibility);
		ReflectionTestUtils.setField(v, "processingStatus", processingStatus);
		ReflectionTestUtils.setField(v, "encodedUrl", encodedKey);
		ReflectionTestUtils.setField(v, "blurredS3Key", blurredKey);
		ReflectionTestUtils.setField(v, "thumbnailUrl", thumbKey);
		ReflectionTestUtils.setField(v, "viewCount", viewCount);
		return v;
	}

	private void givenVideo(Video video) {
		given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(video));
	}

	@Test
	@DisplayName("없는 영상을 조회하면 VIDEO_NOT_FOUND 다")
	void 없는_영상을_조회하면_VIDEO_NOT_FOUND다() {
		given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> videoService.getVideoPlayback(OWNER_ID, VIDEO_ID))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	@DisplayName("삭제된 영상은 소유자가 조회해도 VIDEO_NOT_FOUND 다")
	void 삭제된_영상은_소유자가_조회해도_VIDEO_NOT_FOUND다() {
		givenVideo(video(VideoStatus.DELETED, Visibility.PUBLIC, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 0L));

		assertThatThrownBy(() -> videoService.getVideoPlayback(OWNER_ID, VIDEO_ID))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	@DisplayName("블라인드된 영상을 타인이 조회하면 VIDEO_NOT_FOUND 다")
	void 블라인드된_영상을_타인이_조회하면_VIDEO_NOT_FOUND다() {
		givenVideo(video(VideoStatus.BLINDED, Visibility.PUBLIC, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 0L));

		assertThatThrownBy(() -> videoService.getVideoPlayback(OTHER_ID, VIDEO_ID))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	@DisplayName("블라인드된 영상을 소유자가 조회하면 playbackUrl 은 null 이고 status 는 BLINDED 다")
	void 블라인드된_영상을_소유자가_조회하면_playbackUrl은_null이고_status는_BLINDED다() {
		givenVideo(video(VideoStatus.BLINDED, Visibility.PUBLIC, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 0L));

		VideoPlaybackResponseDto result = videoService.getVideoPlayback(OWNER_ID, VIDEO_ID);

		assertThat(result.playbackUrl()).isNull();
		assertThat(result.status()).isEqualTo("BLINDED");
		assertThat(result.expiresInSec()).isNull();
		verify(videoRepository, never()).incrementViewCount(anyLong());
	}

	@Test
	@DisplayName("비공개 영상을 타인이 조회하면 VIDEO_FORBIDDEN 이다")
	void 비공개_영상을_타인이_조회하면_VIDEO_FORBIDDEN이다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PRIVATE, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 0L));

		assertThatThrownBy(() -> videoService.getVideoPlayback(OTHER_ID, VIDEO_ID))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_FORBIDDEN);
	}

	@Test
	@DisplayName("비공개 READY 영상을 소유자가 조회하면 재생URL이 발급된다")
	void 비공개_READY_영상을_소유자가_조회하면_재생URL이_발급된다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PRIVATE, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 0L));

		VideoPlaybackResponseDto result = videoService.getVideoPlayback(OWNER_ID, VIDEO_ID);

		assertThat(result.playbackUrl()).startsWith("https://").contains(ENCODED_KEY);
		assertThat(result.visibility()).isEqualTo("PRIVATE");
		verify(videoRepository, never()).incrementViewCount(anyLong());
	}

	@Test
	@DisplayName("공개 READY 영상을 타인이 조회하면 재생URL이 발급된다")
	void 공개_READY_영상을_타인이_조회하면_재생URL이_발급된다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 0L));

		VideoPlaybackResponseDto result = videoService.getVideoPlayback(OTHER_ID, VIDEO_ID);

		assertThat(result.playbackUrl()).startsWith("https://").contains(ENCODED_KEY);
		assertThat(result.thumbnailUrl()).contains(THUMB_KEY);
	}

	@Test
	@DisplayName("공개 영상이 인코딩중이면 playbackUrl 은 null 이고 processingStatus 를 반환한다")
	void 공개_영상이_인코딩중이면_playbackUrl은_null이고_processingStatus를_반환한다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.ENCODING,
			null, null, null, 0L));

		VideoPlaybackResponseDto result = videoService.getVideoPlayback(OTHER_ID, VIDEO_ID);

		assertThat(result.playbackUrl()).isNull();
		assertThat(result.thumbnailUrl()).isNull();
		assertThat(result.processingStatus()).isEqualTo("ENCODING");
	}

	@Test
	@DisplayName("블러본이 있으면 encoded 가 아니라 블러본 key 로 presign 한다")
	void 블러본이_있으면_encoded가_아니라_블러본_key로_presign한다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.READY,
			ENCODED_KEY, BLURRED_KEY, THUMB_KEY, 0L));

		VideoPlaybackResponseDto result = videoService.getVideoPlayback(OWNER_ID, VIDEO_ID);

		assertThat(result.playbackUrl()).contains(BLURRED_KEY).doesNotContain(ENCODED_KEY);
	}

	@Test
	@DisplayName("블러본이 없으면 encoded key 로 presign 한다")
	void 블러본이_없으면_encoded_key로_presign한다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 0L));

		VideoPlaybackResponseDto result = videoService.getVideoPlayback(OWNER_ID, VIDEO_ID);

		assertThat(result.playbackUrl()).contains(ENCODED_KEY);
	}

	@Test
	@DisplayName("타인이 공개 READY 영상을 재생하면 view_count 가 증가한다")
	void 타인이_공개_READY_영상을_재생하면_view_count가_증가한다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 37L));

		VideoPlaybackResponseDto result = videoService.getVideoPlayback(OTHER_ID, VIDEO_ID);

		verify(videoRepository).incrementViewCount(VIDEO_ID);
		// 응답 viewCount 는 증가 전 스냅샷이다(§설계 M7).
		assertThat(result.viewCount()).isEqualTo(37L);
	}

	@Test
	@DisplayName("소유자 본인이 재생해도 view_count 는 증가하지 않는다")
	void 소유자_본인이_재생해도_view_count는_증가하지_않는다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 37L));

		videoService.getVideoPlayback(OWNER_ID, VIDEO_ID);

		verify(videoRepository, never()).incrementViewCount(anyLong());
	}

	@Test
	@DisplayName("재생URL을 발급하지 못한 조회는 view_count 가 증가하지 않는다")
	void 재생URL을_발급하지_못한_조회는_view_count가_증가하지_않는다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.ENCODING,
			null, null, null, 0L));

		VideoPlaybackResponseDto result = videoService.getVideoPlayback(OTHER_ID, VIDEO_ID);

		assertThat(result.playbackUrl()).isNull();
		verify(videoRepository, never()).incrementViewCount(anyLong());
	}

	@Test
	@DisplayName("READY 지만 재생 key 가 없으면 playbackUrl 과 expiresInSec 가 모두 null 이다")
	void READY지만_재생_key가_없으면_playbackUrl과_expiresInSec가_모두_null이다() {
		// blurred·encoded 둘 다 null 인 기형 READY 행 — 스키마는 허용, markReady 정상 경로엔 불가.
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.READY,
			null, null, null, 0L));

		VideoPlaybackResponseDto result = videoService.getVideoPlayback(OTHER_ID, VIDEO_ID);

		assertThat(result.playbackUrl()).isNull();
		assertThat(result.expiresInSec()).isNull();
		verify(videoRepository, never()).incrementViewCount(anyLong());
	}

	@Test
	@DisplayName("expiresInSec 는 playbackUrl 발급시 presign TTL 과 같고 null 이면 null 이다")
	void expiresInSec는_playbackUrl_발급시_presign_TTL과_같고_null이면_null이다() {
		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.READY,
			ENCODED_KEY, null, THUMB_KEY, 0L));
		VideoPlaybackResponseDto issued = videoService.getVideoPlayback(OWNER_ID, VIDEO_ID);
		assertThat(issued.playbackUrl()).isNotNull();
		assertThat(issued.expiresInSec()).isEqualTo(thumbnailUrlPresigner.ttlSeconds());

		givenVideo(video(VideoStatus.ACTIVE, Visibility.PUBLIC, ProcessingStatus.ENCODING,
			null, null, null, 0L));
		VideoPlaybackResponseDto notIssued = videoService.getVideoPlayback(OWNER_ID, VIDEO_ID);
		assertThat(notIssued.playbackUrl()).isNull();
		assertThat(notIssued.expiresInSec()).isNull();
	}
}
