package com.msg.fillmap.user.service;

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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.service.RefreshTokenService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.ProfileImagePresignRequestDto;
import com.msg.fillmap.user.dto.ProfileImagePresignResponseDto;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 프로필 이미지 presigned URL 발급 (MSG-373 §API 1). presign 은 네트워크 호출 없는 순수 로컬 서명
 * 연산이라 더미 자격증명으로 실제 S3Presigner 를 쓴다 — 실제 서명기여야 URL 포맷과 서명 헤더를
 * 검증할 수 있다 (VideoPresignTest 와 같은 이유·같은 방식). DB·S3 를 타지 않아 컨텍스트가 불필요하다.
 */
@DisplayName("프로필 이미지 presigned URL 발급")
class UserProfileImagePresignTest {

	private static final long MAX_PROFILE_IMAGE_BYTES = 5L * 1024 * 1024;
	private static final Long USER_ID = 42L;

	private UserService userService;

	@BeforeEach
	void setUp() {
		S3Presigner presigner = S3Presigner.builder()
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
			.build();
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));

		userService = new UserServiceImpl(mock(UserRepository.class), mock(RefreshTokenService.class),
			mock(TokenProvider.class), presigner, mock(S3Client.class), properties);
	}

	private ProfileImagePresignResponseDto issue(String extension, String contentType, long contentLength) {
		return userService.issueProfileImagePresignedUrl(
			USER_ID, new ProfileImagePresignRequestDto(extension, contentType, contentLength));
	}

	@Nested
	@DisplayName("허용 요청")
	class 허용_요청 {

		// 검증: FR-USER-12
		@Test
		@DisplayName("확장자와 타입 쌍이 맞으면 presigned URL 을 발급한다 (FR-1)")
		void 허용_확장자와_타입_쌍이_맞으면_presigned_URL을_발급한다() {
			ProfileImagePresignResponseDto response = issue("jpg", "image/jpeg", 1048576L);

			assertThat(response.uploadUrl()).contains("fillmap-video-dev");
			assertThat(response.expiresInSec()).isEqualTo(600L);
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("발급 키는 내 pending 경로 아래에 생성된다 (§D-2)")
		void 발급_키는_내_pending_경로_아래에_생성된다() {
			assertThat(issue("png", "image/png", 1048576L).s3Key())
				.matches("profiles/pending/42/[0-9a-f-]{36}\\.png");
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("확장자 대문자는 소문자로 정규화해 발급한다")
		void 확장자_대문자는_소문자로_정규화해_발급한다() {
			assertThat(issue("JPG", "image/jpeg", 1048576L).s3Key()).endsWith(".jpg");
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("상한 5MB 정확히는 통과한다 (경계 안)")
		void 상한_5MB_정확히는_통과한다() {
			assertThat(issue("webp", "image/webp", MAX_PROFILE_IMAGE_BYTES).s3Key()).endsWith(".webp");
		}

		/**
		 * 크기·타입 강제가 실제로 성립하는지 보는 지점 — 서명에 빠지면 클라이언트가 선언과 다른 파일을
		 * 올려도 S3 가 막지 못한다. SDK 업그레이드로 서명 헤더 구성이 바뀌면 여기서 회귀를 잡는다.
		 */
		// 검증: FR-USER-12
		@Test
		@DisplayName("presigned URL 은 content-length 와 content-type 을 서명한다")
		void presigned_URL은_크기와_타입을_서명한다() {
			assertThat(signedHeadersOf(issue("jpg", "image/jpeg", 1048576L).uploadUrl()))
				.contains("content-length", "content-type");
		}
	}

	@Nested
	@DisplayName("거부 요청")
	class 거부_요청 {

		// 검증: FR-USER-12
		@Test
		@DisplayName("지원하지 않는 확장자는 1415 로 거부한다 (FR-5)")
		void 지원하지_않는_확장자는_1415로_거부한다() {
			assertThatThrownBy(() -> issue("gif", "image/gif", 1048576L))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.UNSUPPORTED_IMAGE_EXTENSION);
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("아이폰 사진 형식(heic·heif)은 1415 로 거부한다 — 표시되지 않는 이미지를 받지 않는다 (§D-4 재확정)")
		void 아이폰_사진_형식은_1415로_거부한다() {
			// 저장은 되지만 대부분의 브라우저가 표시하지 못하는 모순을 서버에서 닫는다. 정상 경로는 iOS 가
			// JPEG 로 변환해 주므로 이 거부에 걸리지 않는다 (2026-08-11 재확정).
			assertThatThrownBy(() -> issue("heic", "image/heic", 1048576L))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.UNSUPPORTED_IMAGE_EXTENSION);
			assertThatThrownBy(() -> issue("heif", "image/heif", 1048576L))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.UNSUPPORTED_IMAGE_EXTENSION);
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("확장자와 Content-Type 이 어긋나면 1415 로 거부한다 (MSG-64 D2 미러)")
		void 확장자와_ContentType이_어긋나면_1415로_거부한다() {
			assertThatThrownBy(() -> issue("png", "image/jpeg", 1048576L))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.UNSUPPORTED_IMAGE_EXTENSION);
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("선언 크기가 5MB 를 넘으면 1413 으로 거부한다 (FR-5)")
		void 선언_크기가_5MB를_넘으면_1413으로_거부한다() {
			assertThatThrownBy(() -> issue("jpg", "image/jpeg", MAX_PROFILE_IMAGE_BYTES + 1))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.IMAGE_TOO_LARGE);
		}
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
