package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
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

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.video.dto.GridCoverVideoResponseDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 전역 대표 조회 + 썸네일 presign (MSG-87). 정렬·필터는 repository 계약이라 VideoGlobalCoverQueryTest 가 실
 * DB 로 검증하고, 여기서는 서비스가 대표 1건을 presigned URL 로 매핑하는지·없으면 null 을 주는지만 본다.
 * presign 은 네트워크 없는 로컬 서명이라 더미 자격증명으로 실제 S3Presigner 를 쓴다(VideoPresignTest 전략).
 */
@DisplayName("VideoService 격자 전역 대표 조회")
class VideoGlobalCoverServiceTest {

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
			mock(RegionStatsCommandService.class));
	}

	private Video readyVideo(long id, String thumbKey, LocalDateTime recordedAt, long viewCount) {
		Video video = Video.create(1L, GRID_ID, "videos/original/x.mp4", null, (short) 12, recordedAt);
		video.markReady("videos/encoded/" + id + ".mp4", thumbKey);
		ReflectionTestUtils.setField(video, "id", id);
		ReflectionTestUtils.setField(video, "viewCount", viewCount);
		return video;
	}

	@Test
	@DisplayName("대표 영상의 썸네일은 presigned GET URL 로 발급된다")
	void 대표_영상의_썸네일은_presigned_GET_URL로_발급된다() {
		LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 20, 18, 3, 11);
		given(videoRepository.findGlobalCover(GRID_ID))
			.willReturn(Optional.of(readyVideo(1042L, THUMB_KEY, recordedAt, 37L)));

		GridCoverVideoResponseDto result = videoService.getGridCover(GRID_ID);

		assertThat(result.videoId()).isEqualTo(1042L);
		assertThat(result.viewCount()).isEqualTo(37L);
		assertThat(result.recordedAt()).isEqualTo(recordedAt);
		assertThat(result.thumbnailUrl())
			.startsWith("https://")
			.contains("fillmap-video-dev")
			.contains("videos/thumb/1042.jpg");
	}

	@Test
	@DisplayName("발급된 썸네일 URL 은 서명파라미터를 포함한다")
	void 발급된_썸네일_URL은_서명파라미터를_포함한다() {
		given(videoRepository.findGlobalCover(GRID_ID))
			.willReturn(Optional.of(readyVideo(1042L, THUMB_KEY, LocalDateTime.now(), 0L)));

		String thumbnailUrl = videoService.getGridCover(GRID_ID).thumbnailUrl();

		assertThat(thumbnailUrl)
			.contains("X-Amz-Algorithm")
			.contains("X-Amz-Signature")
			.contains("X-Amz-Expires");
	}

	@Test
	@DisplayName("대표가 없으면 null 을 반환한다")
	void 대표가_없으면_null을_반환한다() {
		given(videoRepository.findGlobalCover(GRID_ID)).willReturn(Optional.empty());

		assertThat(videoService.getGridCover(GRID_ID)).isNull();
	}

	@Test
	@DisplayName("view_count 가 모두 0이면 최신 영상이 대표가 된다")
	void view_count가_모두_0이면_최신_영상이_대표가_된다() {
		// view_count 전부 0일 때의 최신순 폴백은 repository 정렬(view_count DESC, created_at DESC)이 보장한다.
		// 서비스는 repository 가 고른 그 1건을 그대로 매핑한다 — 여기서는 최신 영상이 넘어온다고 두고 매핑을 본다.
		LocalDateTime newest = LocalDateTime.of(2026, 7, 20, 12, 0, 0);
		given(videoRepository.findGlobalCover(GRID_ID))
			.willReturn(Optional.of(readyVideo(2L, THUMB_KEY, newest, 0L)));

		GridCoverVideoResponseDto result = videoService.getGridCover(GRID_ID);

		assertThat(result.videoId()).isEqualTo(2L);
		assertThat(result.viewCount()).isEqualTo(0L);
	}

	@Test
	@DisplayName("대표 응답에 작성자 정보가 포함되지 않는다")
	void 대표_응답에_작성자_정보가_포함되지_않는다() {
		// 프라이버시: 전역 대표는 타인 영상일 수 있어 작성자 식별 정보를 담지 않는다(스펙 §API).
		List<String> fields = Arrays.stream(GridCoverVideoResponseDto.class.getRecordComponents())
			.map(RecordComponent::getName)
			.toList();

		assertThat(fields)
			.containsExactly("videoId", "thumbnailUrl", "durationSec", "viewCount", "recordedAt")
			.noneMatch(name -> name.toLowerCase().contains("author")
				|| name.toLowerCase().contains("nickname")
				|| name.toLowerCase().contains("user"));
	}
}
