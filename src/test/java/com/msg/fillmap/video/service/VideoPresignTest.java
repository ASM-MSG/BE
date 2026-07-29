package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.badge.repository.UserBadgeRepository;
import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.PresignedUrlResponseDto;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * presign 은 네트워크 호출 없는 순수 로컬 서명 연산이라 더미 자격증명으로 실제 S3Presigner 를 쓴다.
 * (LocalStack·presigner mock 불필요 — 실제 서명기여야 URL 포맷과 서명 헤더를 검증할 수 있다.)
 */
@DisplayName("VideoService presigned URL 발급")
class VideoPresignTest {

	private static final long MAX_UPLOAD_BYTES = 104857600L;   // 100MB
	private static final long USER_ID = 42L;

	private VideoService videoService;

	@BeforeEach
	void setUp() {
		S3Presigner presigner = S3Presigner.builder()
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
			.build();
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", MAX_UPLOAD_BYTES));

		// presign 경로는 DB 도 인코딩도 S3 조회도 타지 않는다.
		videoService = new VideoServiceImpl(
			mock(VideoRepository.class), mock(VideoEncodingService.class), mock(VideoStatusWriter.class),
			presigner, mock(software.amazon.awssdk.services.s3.S3Client.class), properties,
			mock(RegionStatsCommandService.class), mock(ThumbnailUrlPresigner.class),
			mock(BadgeAwardService.class), mock(UserBadgeRepository.class));
	}

	@Test
	void mp4_요청이면_s3Key가_videos_pending_userId_uuid_mp4_형식이다() {
		PresignedUrlResponseDto response = videoService.issuePresignedUrl(
			USER_ID, new PresignedUrlRequestDto("mp4", "video/mp4", 8388608L));

		assertThat(response.s3Key()).matches("videos/pending/42/[0-9a-f-]{36}\\.mp4");
		assertThat(response.uploadUrl()).contains("fillmap-video-dev");
		assertThat(response.expiresInSec()).isEqualTo(600L);
	}

	@Test
	void avi_확장자는_UNSUPPORTED_EXTENSION_을_던진다() {
		assertThatThrownBy(() -> videoService.issuePresignedUrl(
			USER_ID, new PresignedUrlRequestDto("avi", "video/x-msvideo", 8388608L)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.UNSUPPORTED_EXTENSION);
	}

	@Test
	void mp4에_video_quicktime_을_보내면_거부한다() {
		assertThatThrownBy(() -> videoService.issuePresignedUrl(
			USER_ID, new PresignedUrlRequestDto("mp4", "video/quicktime", 8388608L)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.UNSUPPORTED_EXTENSION);
	}

	@Test
	void 상한_초과_크기는_FILE_TOO_LARGE_를_던진다() {
		assertThatThrownBy(() -> videoService.issuePresignedUrl(
			USER_ID, new PresignedUrlRequestDto("mp4", "video/mp4", MAX_UPLOAD_BYTES + 1)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.FILE_TOO_LARGE);
	}

	/**
	 * MSG-64 D3 의 크기 강제가 실제로 성립하는지 확인하는 지점.
	 * content-length 가 서명되지 않으면 클라이언트가 선언과 다른 크기를 올려도 S3 가 막지 못한다.
	 * AWS SDK 업그레이드로 서명 헤더 구성이 바뀌면 이 테스트가 회귀를 잡는다.
	 */
	@Test
	void presigned_URL_은_content_length_와_content_type_을_서명한다() {
		PresignedUrlResponseDto response = videoService.issuePresignedUrl(
			USER_ID, new PresignedUrlRequestDto("mp4", "video/mp4", 8388608L));

		assertThat(signedHeadersOf(response.uploadUrl()))
			.contains("content-length", "content-type");
	}

	/** presigned URL 의 X-Amz-SignedHeaders 쿼리 파라미터 값(세미콜론 구분)을 꺼낸다. */
	private static List<String> signedHeadersOf(String uploadUrl) {
		String query = URLDecoder.decode(URI.create(uploadUrl).getQuery(), StandardCharsets.UTF_8);
		return Arrays.stream(query.split("&"))
			.filter(param -> param.startsWith("X-Amz-SignedHeaders="))
			.map(param -> param.substring("X-Amz-SignedHeaders=".length()))
			.flatMap(value -> Arrays.stream(value.split(";")))
			.toList();
	}
}
