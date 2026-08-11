package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.service.RefreshTokenService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 프로필 이미지 변경 확정·제거의 S3 축 (MSG-373 §API 2·3, §D-5).
 *
 * 트랜잭션 없이 돌린다 — 이전 객체 정리는 afterCommit 에 등록되므로 폴백(즉시 실행)으로 관찰한다
 * (UserAccountS3CleanupTest·VideoS3CleanupTest 가 같은 이유로 같은 방식). 실 DB 통합 테스트는
 * @Transactional 롤백 격리라 커밋이 없어 이 축을 볼 수 없다 — 저장·조회 축은 그쪽이 맡는다.
 */
@DisplayName("프로필 이미지 확정·제거 — 키 검증과 S3 정리")
class UserProfileImageS3Test {

	private static final long MAX_PROFILE_IMAGE_BYTES = 5L * 1024 * 1024;
	private static final Long USER_ID = 42L;
	private static final String PENDING_KEY = "profiles/pending/42/11111111-1111-1111-1111-111111111111.jpg";
	private static final String URL_BASE = "https://fillmap-video-dev.s3.ap-northeast-2.amazonaws.com/";

	private UserRepository userRepository;
	private S3Client s3Client;
	private UserService userService;
	private User user;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		s3Client = mock(S3Client.class);
		user = User.createLocalUser("profile-image@example.com", "hash", "채우미");
		given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength(1048576L).build());
		given(s3Client.copyObject(any(CopyObjectRequest.class))).willReturn(CopyObjectResponse.builder().build());
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willReturn(DeleteObjectsResponse.builder().build());

		userService = new UserServiceImpl(userRepository, mock(RefreshTokenService.class), mock(TokenProvider.class),
			mock(S3Presigner.class), s3Client,
			new AwsProperties("ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L)));
	}

	private List<String> deletedKeys() {
		ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
		then(s3Client).should().deleteObjects(captor.capture());
		return captor.getValue().delete().objects().stream().map(ObjectIdentifier::key).toList();
	}

	@Nested
	@DisplayName("변경 확정")
	class 변경_확정 {

		// 검증: FR-USER-12
		@Test
		@DisplayName("확정하면 pending 을 original 로 복사하고 공개 URL 을 저장한다 (§D-1·D-2)")
		void 확정하면_공개_URL이_저장되고_갱신된_프로필이_반환된다() {
			String profileImageUrl = userService.updateProfileImage(USER_ID, PENDING_KEY).profileImageUrl();

			assertThat(profileImageUrl).matches(URL_BASE + "profiles/original/42/[0-9a-f-]{36}\\.jpg");
			assertThat(user.getProfileImageUrl()).isEqualTo(profileImageUrl);

			ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
			then(s3Client).should().copyObject(captor.capture());
			assertThat(captor.getValue().sourceKey()).isEqualTo(PENDING_KEY);
			assertThat(captor.getValue().destinationKey()).isEqualTo(profileImageUrl.substring(URL_BASE.length()));
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("확정마다 새 UUID 라 URL 이 바뀐다 — 브라우저 캐시 무효화")
		void 확정마다_새_UUID로_URL이_바뀐다() {
			String first = userService.updateProfileImage(USER_ID, PENDING_KEY).profileImageUrl();
			String second = userService.updateProfileImage(USER_ID, PENDING_KEY).profileImageUrl();

			assertThat(second).isNotEqualTo(first);
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("교체하면 이전 이미지 객체가 커밋 후 삭제된다 (§D-5)")
		void 교체하면_이전_이미지_객체가_커밋_후_삭제된다() {
			String previousKey = "profiles/original/42/00000000-0000-0000-0000-000000000000.png";
			user.changeProfileImage(URL_BASE + previousKey);

			userService.updateProfileImage(USER_ID, PENDING_KEY);

			// pending 원본은 지우지 않는다 — 라이프사이클이 쓸어간다 (영상 선례).
			assertThat(deletedKeys()).containsExactly(previousKey);
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("첫 등록은 지울 이전 객체가 없어 삭제 호출이 없다")
		void 첫_등록은_삭제_호출이_없다() {
			userService.updateProfileImage(USER_ID, PENDING_KEY);

			then(s3Client).should(never()).deleteObjects(any(DeleteObjectsRequest.class));
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("다른 사용자의 pending 키는 1401 로 거부한다 — S3 호출 전에 막힌다")
		void 다른_사용자의_pending_키는_1401로_거부한다() {
			assertThatThrownBy(() -> userService.updateProfileImage(
				USER_ID, "profiles/pending/43/11111111-1111-1111-1111-111111111111.jpg"))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.INVALID_IMAGE_KEY);

			then(s3Client).should(never()).headObject(any(HeadObjectRequest.class));
			then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("확정본 경로나 확장자 없는 키도 1401 이다")
		void 확정본_경로나_확장자_없는_키도_1401이다() {
			assertThatThrownBy(() -> userService.updateProfileImage(
				USER_ID, "profiles/original/42/11111111-1111-1111-1111-111111111111.jpg"))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.INVALID_IMAGE_KEY);

			assertThatThrownBy(() -> userService.updateProfileImage(USER_ID, "profiles/pending/42/no-extension"))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.INVALID_IMAGE_KEY);
		}

		/**
		 * 확정본 프리픽스는 공개 읽기라 svg 가 들어가면 그 S3 도메인에서 스크립트가 실행된다.
		 * presign 이 허용 형식만 서명해 그런 객체가 생길 경로는 없지만, 공개 경로로 가는 마지막 관문에서 막는다.
		 */
		// 검증: FR-USER-12
		@Test
		@DisplayName("허용 밖 확장자 키는 실존 확인 전에 1401 로 막는다")
		void 허용_밖_확장자_키는_1401로_막는다() {
			assertThatThrownBy(() -> userService.updateProfileImage(
				USER_ID, "profiles/pending/42/11111111-1111-1111-1111-111111111111.svg"))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.INVALID_IMAGE_KEY);

			then(s3Client).should(never()).headObject(any(HeadObjectRequest.class));
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("heic 키는 확정에서도 막힌다 — 화이트리스트가 발급과 같은 목록이다 (§D-4 재확정)")
		void heic_키는_확정에서도_막힌다() {
			// 발급이 heic 를 서명하지 않으므로 이 키는 presign 을 우회했다는 뜻이다. 공개 경로로 가는
			// 마지막 관문이 발급과 같은 맵을 보기 때문에 형식 축소가 두 경로에 동시에 적용된다.
			assertThatThrownBy(() -> userService.updateProfileImage(
				USER_ID, "profiles/pending/42/11111111-1111-1111-1111-111111111111.heic"))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.INVALID_IMAGE_KEY);

			then(s3Client).should(never()).headObject(any(HeadObjectRequest.class));
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("S3 에 없는 키는 1402 로 거부한다 — prefix 만으론 지어낸 키를 못 막는다")
		void S3에_없는_키는_1402로_거부한다() {
			given(s3Client.headObject(any(HeadObjectRequest.class)))
				.willThrow(NoSuchKeyException.builder().message("없는 키").build());

			assertThatThrownBy(() -> userService.updateProfileImage(USER_ID, PENDING_KEY))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.IMAGE_UPLOAD_NOT_FOUND);

			then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("실측 크기가 5MB 를 넘으면 1413 으로 거부한다 — 선언값만 믿지 않는다 (MSG-132 P1-1)")
		void 실측_크기가_5MB를_넘으면_1413으로_거부한다() {
			given(s3Client.headObject(any(HeadObjectRequest.class)))
				.willReturn(HeadObjectResponse.builder().contentLength(MAX_PROFILE_IMAGE_BYTES + 1).build());

			assertThatThrownBy(() -> userService.updateProfileImage(USER_ID, PENDING_KEY))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.IMAGE_TOO_LARGE);

			then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
			assertThat(user.getProfileImageUrl()).isNull();
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("없는 유저의 확정은 1404 다")
		void 없는_유저의_확정은_1404다() {
			given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> userService.updateProfileImage(USER_ID, PENDING_KEY))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
		}
	}

	@Nested
	@DisplayName("제거")
	class 제거 {

		// 검증: FR-USER-12
		@Test
		@DisplayName("제거하면 URL 이 null 이 되고 기존 객체가 삭제된다 (FR-6)")
		void 제거하면_URL이_null이_되고_기존_객체가_삭제된다() {
			String previousKey = "profiles/original/42/00000000-0000-0000-0000-000000000000.png";
			user.changeProfileImage(URL_BASE + previousKey);

			assertThat(userService.removeProfileImage(USER_ID).profileImageUrl()).isNull();
			assertThat(user.getProfileImageUrl()).isNull();
			assertThat(deletedKeys()).containsExactly(previousKey);
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("이미 기본 상태면 제거는 삭제 호출 없이 성공한다 (멱등)")
		void 이미_기본_상태면_제거는_삭제_호출_없이_성공한다() {
			assertThat(userService.removeProfileImage(USER_ID).profileImageUrl()).isNull();

			then(s3Client).should(never()).deleteObjects(any(DeleteObjectsRequest.class));
		}

		// 검증: FR-USER-12
		@Test
		@DisplayName("정리 실패가 응답을 실패로 만들지 않는다 — 커밋된 변경이 500 으로 보이면 안 된다")
		void 정리_실패가_응답을_실패로_만들지_않는다() {
			user.changeProfileImage(URL_BASE + "profiles/original/42/old.png");
			given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
				.willThrow(SdkException.builder().message("네트워크 단절").build());

			assertThatCode(() -> userService.removeProfileImage(USER_ID)).doesNotThrowAnyException();
			assertThat(user.getProfileImageUrl()).isNull();
		}
	}
}
