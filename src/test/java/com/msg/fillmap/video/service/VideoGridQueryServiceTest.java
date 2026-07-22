package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.video.dto.GridVideoResponseDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 조회 + 썸네일 presign. presign 은 네트워크 없는 로컬 서명이라 더미 자격증명으로 실제 S3Presigner 를
 * 써서 URL 포맷·서명 파라미터를 검증한다 (VideoPresignTest 와 동일 전략). 정렬은 repository 계약이라
 * VideoGridQueryTest 가 실 DB 로 검증하고, 여기서는 서비스가 그 순서를 보존해 매핑하는지만 본다.
 */
@DisplayName("VideoService 격자별 내 영상 조회")
class VideoGridQueryServiceTest {

	private static final long USER_ID = 42L;
	private static final String GRID_ID = "41642_110458";
	private static final String THUMB_KEY = "videos/thumb/1042.jpg";

	private VideoRepository videoRepository;
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

		videoService = new VideoServiceImpl(
			videoRepository, mock(VideoEncodingService.class), mock(VideoStatusWriter.class),
			presigner, mock(S3Client.class), properties,
			mock(RegionStatsCommandService.class), new ThumbnailUrlPresigner(presigner, properties));
	}

	private void givenVideos(Video... videos) {
		given(videoRepository.findByUserIdAndGridIdAndStatusOrderByCreatedAtDesc(USER_ID, GRID_ID, VideoStatus.ACTIVE))
			.willReturn(List.of(videos));
	}

	private Video readyVideo(long id, String thumbKey, LocalDateTime createdAt) {
		Video video = Video.create(USER_ID, GRID_ID, "videos/original/x.mp4", null, (short) 12, createdAt);
		video.markReady("videos/encoded/" + id + ".mp4", thumbKey);
		ReflectionTestUtils.setField(video, "id", id);
		ReflectionTestUtils.setField(video, "createdAt", createdAt);
		return video;
	}

	private Video encodingVideo(long id, LocalDateTime createdAt) {
		Video video = Video.create(USER_ID, GRID_ID, "videos/original/x.mp4", null, (short) 8, createdAt);
		video.markEncoding();
		ReflectionTestUtils.setField(video, "id", id);
		ReflectionTestUtils.setField(video, "createdAt", createdAt);
		return video;
	}

	@Test
	@DisplayName("READY 영상은 썸네일 presigned GET URL 을 발급해 내려준다")
	void READY_영상은_썸네일_presigned_GET_URL을_발급해_내려준다() {
		givenVideos(readyVideo(1042L, THUMB_KEY, LocalDateTime.now()));

		List<GridVideoResponseDto> result = videoService.getGridVideos(USER_ID, GRID_ID);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).thumbnailUrl())
			.startsWith("https://")
			.contains("fillmap-video-dev")
			.contains("videos/thumb/1042.jpg");
		assertThat(result.get(0).processingStatus()).isEqualTo("READY");
	}

	@Test
	@DisplayName("인코딩중 영상은 thumbnailUrl 이 null 이고 processingStatus 가 ENCODING 이다")
	void 인코딩중_영상은_thumbnailUrl이_null이고_processingStatus가_ENCODING이다() {
		givenVideos(encodingVideo(1041L, LocalDateTime.now()));

		List<GridVideoResponseDto> result = videoService.getGridVideos(USER_ID, GRID_ID);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).thumbnailUrl()).isNull();
		assertThat(result.get(0).processingStatus()).isEqualTo("ENCODING");
	}

	@Test
	@DisplayName("존재하지 않는 격자를 조회하면 빈 리스트를 반환한다")
	void 존재하지_않는_격자를_조회하면_빈_리스트를_반환한다() {
		given(videoRepository.findByUserIdAndGridIdAndStatusOrderByCreatedAtDesc(
			USER_ID, "9999999_9999999", VideoStatus.ACTIVE)).willReturn(List.of());

		List<GridVideoResponseDto> result = videoService.getGridVideos(USER_ID, "9999999_9999999");

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("조회결과는 createdAt 내림차순이다")
	void 조회결과는_createdAt_내림차순이다() {
		LocalDateTime newer = LocalDateTime.of(2026, 7, 20, 12, 0, 0);
		LocalDateTime older = LocalDateTime.of(2026, 7, 20, 10, 0, 0);
		givenVideos(readyVideo(2L, THUMB_KEY, newer), encodingVideo(1L, older));

		List<GridVideoResponseDto> result = videoService.getGridVideos(USER_ID, GRID_ID);

		assertThat(result).extracting(GridVideoResponseDto::createdAt).containsExactly(newer, older);
	}

	@Test
	@DisplayName("발급된 썸네일 URL 은 서명파라미터를 포함한다")
	void 발급된_썸네일_URL은_서명파라미터를_포함한다() {
		givenVideos(readyVideo(1042L, THUMB_KEY, LocalDateTime.now()));

		String thumbnailUrl = videoService.getGridVideos(USER_ID, GRID_ID).get(0).thumbnailUrl();

		assertThat(thumbnailUrl)
			.contains("X-Amz-Algorithm")
			.contains("X-Amz-Signature")
			.contains("X-Amz-Expires");
	}
}
