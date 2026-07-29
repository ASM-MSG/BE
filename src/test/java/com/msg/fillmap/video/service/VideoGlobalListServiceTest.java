package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.GridGlobalVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.video.support.VideoCursor;

/**
 * 전역 목록 조회 + 커서/클램프/presign (MSG-237). 필터·정렬은 repository 계약이라 VideoGlobalListQueryTest 가
 * 실 DB 로 검증하고, 여기서는 서비스의 size 클램프·lookahead 트림·nextCursor 발급·무효 커서 400·썸네일
 * presign 매핑만 본다. presign 은 네트워크 없는 로컬 서명이라 더미 자격증명으로 실제 S3Presigner 를 쓴다
 * (VideoGlobalCoverServiceTest 전략).
 */
@DisplayName("VideoService 격자 전역 영상 목록 조회")
class VideoGlobalListServiceTest {

	private static final String GRID_ID = "41642_110458";

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
			mock(RegionStatsCommandService.class), new ThumbnailUrlPresigner(presigner, properties),
			mock(BadgeAwardService.class), mock(StreakCommandService.class));
	}

	/** 목록 후보 조건(PUBLIC 는 repository 필터 소관)·READY·썸네일 key 를 갖춘 픽스처 — 정상 경로 기준. */
	private Video readyVideo(long id, long viewCount, LocalDateTime createdAt) {
		Video video = Video.create(1L, GRID_ID, "videos/original/" + id + ".mp4", null, (short) 12,
			LocalDateTime.of(2026, 7, 20, 18, 3, 11));
		video.markReady("videos/encoded/" + id + ".mp4", "videos/thumb/" + id + ".jpg");
		ReflectionTestUtils.setField(video, "id", id);
		ReflectionTestUtils.setField(video, "viewCount", viewCount);
		ReflectionTestUtils.setField(video, "createdAt", createdAt);   // @CreationTimestamp 미동작(비영속) 보완
		return video;
	}

	private static LocalDateTime at(int hour) {
		return LocalDateTime.of(2026, 7, 20, hour, 0, 0);
	}

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("각 항목의 썸네일은 presigned GET URL 로 발급된다")
	void 각_항목의_썸네일은_presigned_GET_URL로_발급된다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21))
			.willReturn(List.of(readyVideo(1042L, 37L, at(10))));

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 20);

		assertThat(result.videos()).hasSize(1);
		GridGlobalVideoResponseDto item = result.videos().get(0);
		assertThat(item.videoId()).isEqualTo(1042L);
		assertThat(item.viewCount()).isEqualTo(37L);
		assertThat(item.recordedAt()).isEqualTo(LocalDateTime.of(2026, 7, 20, 18, 3, 11));
		assertThat(item.thumbnailUrl())
			.startsWith("https://")
			.contains("fillmap-video-dev")
			.contains("videos/thumb/1042.jpg");
	}

	@Test
	@DisplayName("발급된 썸네일 URL 은 서명파라미터를 포함한다")
	void 발급된_썸네일_URL은_서명파라미터를_포함한다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21))
			.willReturn(List.of(readyVideo(1042L, 37L, at(10))));

		String thumbnailUrl = videoService.getGridGlobalVideos(GRID_ID, null, 20).videos().get(0).thumbnailUrl();

		assertThat(thumbnailUrl)
			.contains("X-Amz-Algorithm")
			.contains("X-Amz-Signature")
			.contains("X-Amz-Expires");
	}

	@Test
	@DisplayName("다음 페이지가 있으면 hasNext 는 true 이고 nextCursor 가 발급된다")
	void 다음_페이지가_있으면_hasNext는_true이고_nextCursor가_발급된다() {
		LocalDateTime boundary = at(9);
		given(videoRepository.findGlobalVideos(GRID_ID, 3)).willReturn(List.of(
			readyVideo(3L, 30L, at(12)),
			readyVideo(2L, 20L, boundary),
			readyVideo(1L, 10L, at(8))));   // lookahead 초과분 — 응답에선 잘린다

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 2);

		assertThat(result.hasNext()).isTrue();
		assertThat(result.videos()).extracting(GridGlobalVideoResponseDto::videoId).containsExactly(3L, 2L);
		// nextCursor 는 트림된 마지막 항목(id=2)의 경계값 — 라운드트립으로 §D2 와이어 포맷(4성분) 대칭을 확인한다.
		assertThat(VideoCursor.decode(result.nextCursor())).isEqualTo(new VideoCursor(GRID_ID, 20L, boundary, 2L));

		// 발급된 커서를 그대로 되돌려주면 keyset After 조회로 이어진다.
		given(videoRepository.findGlobalVideosAfter(GRID_ID, 20L, boundary, 2L, 3))
			.willReturn(List.of(readyVideo(1L, 10L, at(8))));
		GridVideoPageResponseDto nextPage = videoService.getGridGlobalVideos(GRID_ID, result.nextCursor(), 2);
		assertThat(nextPage.videos()).extracting(GridGlobalVideoResponseDto::videoId).containsExactly(1L);
		assertThat(nextPage.hasNext()).isFalse();
	}

	@Test
	@DisplayName("마지막 페이지면 hasNext 는 false 이고 nextCursor 는 null 이다")
	void 마지막_페이지면_hasNext는_false이고_nextCursor는_null이다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21))
			.willReturn(List.of(readyVideo(1042L, 37L, at(10))));

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 20);

		assertThat(result.videos()).hasSize(1);
		assertThat(result.hasNext()).isFalse();
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	@DisplayName("size 가 상한을 초과하면 상한으로 클램프된다 (§D5)")
	void size가_상한을_초과하면_상한으로_클램프된다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 51)).willReturn(List.of());

		videoService.getGridGlobalVideos(GRID_ID, null, 999);

		then(videoRepository).should().findGlobalVideos(GRID_ID, 51);   // 상한 50 + lookahead 1
	}

	@Test
	@DisplayName("size 가 0 이하면 기본값으로 보정된다 (§D5)")
	void size가_0이하면_기본값으로_보정된다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21)).willReturn(List.of());

		videoService.getGridGlobalVideos(GRID_ID, null, 0);

		then(videoRepository).should().findGlobalVideos(GRID_ID, 21);   // 기본 20 + lookahead 1
	}

	@Test
	@DisplayName("무효 커서는 400 INVALID_CURSOR 다 (developCode 3423)")
	void 무효_커서는_400_INVALID_CURSOR다() {
		List<String> badCursors = List.of(
			"!!!not-base64!!!",
			base64Url("1:2"),                            // 필드 수 위반 (2필드)
			base64Url("5:1784455800000000:1039"),        // 필드 수 위반 — gridId 없는 구 3필드 포맷도 무효다
			base64Url(GRID_ID + ":a:b:c"));              // 타입 위반 (정수 아님)

		for (String bad : badCursors) {
			assertThatThrownBy(() -> videoService.getGridGlobalVideos(GRID_ID, bad, 20))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_CURSOR);
		}
		assertThat(VideoErrorCode.INVALID_CURSOR.getErrorCode()).isEqualTo(3423);
		assertThat(VideoErrorCode.INVALID_CURSOR.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("다른 격자에서 발급된 커서는 400 INVALID_CURSOR 다 (커서 gridId 바인딩)")
	void 다른_격자에서_발급된_커서는_400_INVALID_CURSOR다() {
		// 격자 A 의 커서를 격자 B 요청에 재사용하면 경계값이 B 의 keyset 으로 오적용돼 결과가 조용히
		// 잘린다 — 커서의 gridId 성분을 요청 격자와 대조해 거부한다 (2026-07-28 Codex 교차 리뷰 P2).
		String foreignCursor = VideoCursor.encode("41000_110000", 20L, at(9), 2L);

		assertThatThrownBy(() -> videoService.getGridGlobalVideos(GRID_ID, foreignCursor, 20))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_CURSOR);
		assertThat(VideoErrorCode.INVALID_CURSOR.getErrorCode()).isEqualTo(3423);
	}

	@Test
	@DisplayName("결과가 size 로 나누어떨어지면 마지막 페이지에서 hasNext false 다 (정확 경계 lookahead 회귀)")
	void 결과가_size로_나누어떨어지면_마지막_페이지에서_hasNext_false다() {
		// lookahead(size+1) 요청에 정확히 size 행만 오는 경계 — MSG-90 이 잡았던 회귀 지점이다.
		given(videoRepository.findGlobalVideos(GRID_ID, 3)).willReturn(List.of(
			readyVideo(2L, 20L, at(10)),
			readyVideo(1L, 10L, at(9))));

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 2);

		assertThat(result.videos()).hasSize(2);
		assertThat(result.hasNext()).isFalse();
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	@DisplayName("공개 READY 가 없는 격자를 조회하면 빈 페이지를 반환한다 (예외 아님)")
	void 공개_READY가_없는_격자를_조회하면_빈_페이지를_반환한다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21)).willReturn(List.of());

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 20);

		assertThat(result.videos()).isEmpty();
		assertThat(result.hasNext()).isFalse();
		assertThat(result.nextCursor()).isNull();
	}
}
