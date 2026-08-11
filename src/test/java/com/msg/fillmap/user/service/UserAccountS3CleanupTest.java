package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.service.RefreshTokenService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 계정 삭제 S3 정리 — 어떤 키가 삭제 요청에 담기는지 (MSG-205 FR-3).
 *
 * 트랜잭션 없이 돌린다 — 정리는 afterCommit 에 등록되므로 폴백(즉시 실행)으로 관찰한다
 * (VideoS3CleanupTest 가 같은 이유로 같은 방식을 쓴다). 청크는 단위 테스트 — 실 데이터 1000건 적재 불요.
 */
@DisplayName("계정 삭제 S3 정리 — 요청 키·청크·실패 무해")
class UserAccountS3CleanupTest {

	private static final Long USER_ID = 1L;

	private UserRepository userRepository;
	private RefreshTokenService refreshTokenService;
	private TokenProvider tokenProvider;
	private S3Client s3Client;
	private UserService service;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		refreshTokenService = mock(RefreshTokenService.class);
		tokenProvider = mock(TokenProvider.class);
		s3Client = mock(S3Client.class);
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willReturn(DeleteObjectsResponse.builder().build());
		given(userRepository.deleteUser(USER_ID)).willReturn(1);
		service = new UserServiceImpl(userRepository, refreshTokenService, tokenProvider, s3Client,
			new AwsProperties("ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L)));
	}

	private List<List<String>> deletedKeyBatches() {
		ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
		then(s3Client).should(atLeastOnce()).deleteObjects(captor.capture());
		return captor.getAllValues().stream()
			.map(request -> request.delete().objects().stream().map(ObjectIdentifier::key).toList())
			.toList();
	}

	// 검증: FR-USER-08
	@Test
	void 삭제_시_그_유저_영상의_원본_인코딩본_썸네일_블러본_키가_S3_삭제_요청에_담긴다() {
		// 인코딩 완료 영상(4키 전부) + 미인코딩 영상(원본만, 나머지 null — null 은 요청에서 제외돼야 한다).
		given(userRepository.findAllS3KeysByUserId(USER_ID)).willReturn(List.of(
			new Object[] {"k-original", "k-encoded", "k-thumb", "k-blurred"},
			new Object[] {"k-raw-original", null, null, null}));

		service.deleteAccount(USER_ID, "access-token");

		List<List<String>> batches = deletedKeyBatches();
		assertThat(batches).hasSize(1);
		assertThat(batches.get(0)).containsExactlyInAnyOrder(
			"k-original", "k-encoded", "k-thumb", "k-blurred", "k-raw-original");
	}

	// 검증: FR-USER-08
	@Test
	void 영상이_없으면_S3_삭제_요청을_보내지_않는다() {
		given(userRepository.findAllS3KeysByUserId(USER_ID)).willReturn(List.of());

		service.deleteAccount(USER_ID, "access-token");

		then(s3Client).should(never()).deleteObjects(any(DeleteObjectsRequest.class));
	}

	// 검증: FR-USER-08, FR-USER-09
	@Test
	void S3_삭제_실패는_응답을_실패로_만들지_않는다() {
		given(userRepository.findAllS3KeysByUserId(USER_ID)).willReturn(
			List.<Object[]>of(new Object[] {"k-original", null, null, null}));
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willThrow(SdkException.builder().message("네트워크 단절").build());

		// 커밋 후 정리라 실패해도 사용자 요청은 성공해야 한다 — 삭제는 이미 커밋됐다.
		assertThatCode(() -> service.deleteAccount(USER_ID, "access-token")).doesNotThrowAnyException();

		// S3 실패가 세션 정리까지 막으면 안 된다 (FR-4 는 별도 안전망이 없다).
		then(refreshTokenService).should().deleteAll(USER_ID);
		then(tokenProvider).should().invalidateAccessToken("access-token");
	}

	// 검증: FR-USER-09
	@Test
	void refresh_삭제_실패가_액세스_토큰_블랙리스트를_막지_않는다() {
		given(userRepository.findAllS3KeysByUserId(USER_ID)).willReturn(List.of());
		willThrow(new RuntimeException("Redis 단절")).given(refreshTokenService).deleteAll(USER_ID);

		assertThatCode(() -> service.deleteAccount(USER_ID, "access-token")).doesNotThrowAnyException();

		// 독립 try-catch — refresh 정리 실패가 블랙리스트 시도까지 막으면 안 된다 (Codex 리뷰 반영).
		then(tokenProvider).should().invalidateAccessToken("access-token");
	}

	// 검증: FR-USER-08
	@Test
	void 영상이_1000건을_넘으면_요청이_청크로_분할된다() {
		// 1001개 단일 키 행 → 1000 + 1 두 요청 (DeleteObjects 요청당 상한).
		List<Object[]> rows = LongStream.rangeClosed(1, 1001)
			.mapToObj(i -> new Object[] {"videos/original/1/" + i + ".mp4", null, null, null})
			.toList();
		given(userRepository.findAllS3KeysByUserId(USER_ID)).willReturn(rows);

		service.deleteAccount(USER_ID, "access-token");

		List<List<String>> batches = deletedKeyBatches();
		assertThat(batches).hasSize(2);
		assertThat(batches.get(0)).hasSize(1000);
		assertThat(batches.get(1)).hasSize(1);
		assertThat(batches.stream().flatMap(List::stream)).hasSize(1001).doesNotHaveDuplicates();
	}
}
