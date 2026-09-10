package com.msg.fillmap.event.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignResponseDto;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 신청 대표 이미지의 발급·확정 규칙 (MSG-498 §대표 이미지 확정). presign 은 네트워크 없는 로컬 서명이라
 * 더미 자격증명의 실제 presigner 를 쓰고(VideoPresignTest 선례), 실존·복사만 목 S3Client 다.
 */
@DisplayName("행사 신청 대표 이미지 (MSG-498)")
class EventSubmissionImageStoreTest {

	private static final long USER_ID = 42L;
	private static final long TEN_MB = 10L * 1024 * 1024;
	private static final String PENDING_KEY = "event-submissions/pending/42/8b1c.jpg";

	private S3Client s3Client;
	private EventSubmissionImageStore imageStore;

	@BeforeEach
	void setUp() {
		s3Client = mock(S3Client.class);
		S3Presigner presigner = S3Presigner.builder()
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
			.build();
		AwsProperties properties = new AwsProperties("ap-northeast-2",
			new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));
		imageStore = new EventSubmissionImageStore(presigner, s3Client, properties,
			mock(ThumbnailUrlPresigner.class));
	}

	private void 업로드된_객체가_있다(long contentLength) {
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength(contentLength).build());
		given(s3Client.copyObject(any(CopyObjectRequest.class)))
			.willReturn(CopyObjectResponse.builder().build());
	}

	@Nested
	@DisplayName("presigned URL 발급")
	class Presign {

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("jpg 요청이면 내 pending 경로의 키가 발급된다")
		void jpg_요청이면_내_pending_경로의_키가_발급된다() {
			EventSubmissionImagePresignResponseDto response = imageStore.presign(USER_ID,
				new EventSubmissionImagePresignRequestDto("jpg", "image/jpeg", 1048576L));

			assertThat(response.s3Key()).matches("event-submissions/pending/42/[0-9a-f-]{36}\\.jpg");
			assertThat(response.uploadUrl()).contains("fillmap-video-dev");
			assertThat(response.expiresInSec()).isEqualTo(600L);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("webp 확장자 요청은 거부한다 — 시안 문구가 JPG 또는 PNG 라 프로필 이미지와 다르다")
		void webp_확장자_presign_요청은_거부한다() {
			assertThatThrownBy(() -> imageStore.presign(USER_ID,
				new EventSubmissionImagePresignRequestDto("webp", "image/webp", 1048576L)))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_IMAGE_UNSUPPORTED);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("확장자와 contentType 이 어긋나면 거부한다")
		void 확장자와_contentType이_어긋나면_거부한다() {
			assertThatThrownBy(() -> imageStore.presign(USER_ID,
				new EventSubmissionImagePresignRequestDto("png", "image/jpeg", 1048576L)))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_IMAGE_UNSUPPORTED);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("10MB 초과 선언은 거부한다")
		void 초과_선언은_거부한다() {
			assertThatThrownBy(() -> imageStore.presign(USER_ID,
				new EventSubmissionImagePresignRequestDto("jpg", "image/jpeg", TEN_MB + 1)))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_IMAGE_TOO_LARGE);
		}
	}

	@Nested
	@DisplayName("확정")
	class Confirm {

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("확정하면 original 로 복사되고 키의 uuid 는 새로 발급된다 — 신청마다 객체를 독점 소유한다")
		void 확정하면_original_로_복사되고_uuid가_새로_발급된다() {
			업로드된_객체가_있다(1024L);

			String originalKey = imageStore.confirm(USER_ID, PENDING_KEY);

			assertThat(originalKey).matches("event-submissions/original/42/[0-9a-f-]{36}\\.jpg");
			ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
			then(s3Client).should().copyObject(captor.capture());
			assertThat(captor.getValue().sourceKey()).isEqualTo(PENDING_KEY);
			assertThat(captor.getValue().destinationKey()).isEqualTo(originalKey);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("복사에 성공한 pending 은 지운다 — 같은 키의 재확정이 HEAD 실패로 막힌다")
		void 같은_pending_키의_재확정은_실패한다() {
			업로드된_객체가_있다(1024L);
			// 트랜잭션 밖이라 커밋 후 정리가 즉시 실행된다 (afterCommit 폴백).
			imageStore.confirm(USER_ID, PENDING_KEY);

			ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
			then(s3Client).should().deleteObject(captor.capture());
			assertThat(captor.getValue().key()).isEqualTo(PENDING_KEY);

			// 지워진 뒤의 재확정 시도는 실존 검사에서 걸린다.
			given(s3Client.headObject(any(HeadObjectRequest.class)))
				.willThrow(NoSuchKeyException.builder().build());
			assertThatThrownBy(() -> imageStore.confirm(USER_ID, PENDING_KEY))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_IMAGE_NOT_UPLOADED);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("남의 pending 키와 허용 밖 확장자 키는 거부한다 — S3 를 건드리기 전에 걸린다")
		void 남의_pending_키로_신청하면_거부한다() {
			for (String invalid : new String[] {
				"event-submissions/pending/99/8b1c.jpg",
				"event-submissions/original/42/8b1c.jpg",
				"event-submissions/pending/42/8b1c.svg",
				"event-submissions/pending/42/8b1c"}) {
				assertThatThrownBy(() -> imageStore.confirm(USER_ID, invalid))
					.isInstanceOf(ApiException.class)
					.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_IMAGE_KEY_INVALID);
			}
			then(s3Client).shouldHaveNoInteractions();
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("0바이트 객체는 업로드되지 않은 것으로 본다 — 빈 이미지가 대표 이미지로 저장되지 않는다")
		void 바이트_이미지_객체는_거부한다() {
			업로드된_객체가_있다(0L);

			assertThatThrownBy(() -> imageStore.confirm(USER_ID, PENDING_KEY))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_IMAGE_NOT_UPLOADED);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("실측 크기가 10MB 를 넘으면 거부한다 — presign 을 우회해 올린 파일을 확정에서 잡는다")
		void 실측_크기가_10MB를_넘으면_거부한다() {
			업로드된_객체가_있다(TEN_MB + 1);

			assertThatThrownBy(() -> imageStore.confirm(USER_ID, PENDING_KEY))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_IMAGE_TOO_LARGE);
		}
	}

	@Nested
	@DisplayName("승인 공개 사본 (MSG-500)")
	class MissionCopy {

		private static final String ORIGINAL_KEY = "event-submissions/original/42/8b1c.png";

		@BeforeEach
		void 트랜잭션을_연다() {
			// 보상 등록은 동기화가 살아 있을 때만 걸린다 — 승인 트랜잭션 안에서 도는 실제 상황을 흉내낸다.
			TransactionSynchronizationManager.initSynchronization();
		}

		@AfterEach
		void 트랜잭션을_닫는다() {
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.clearSynchronization();
			}
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("승인 사본은 미션 공개 프리픽스로 복사되고 확장자를 유지한다 — 원본은 남는다")
		void 승인_이미지는_공개_프리픽스로_복사된다() {
			업로드된_객체가_있다(1024L);

			String publicKey = imageStore.copyToMissionImage(ORIGINAL_KEY);

			assertThat(publicKey).matches("missions/org-submission/[0-9a-f-]{36}\\.png");
			ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
			then(s3Client).should().copyObject(captor.capture());
			assertThat(captor.getValue().sourceKey()).isEqualTo(ORIGINAL_KEY);
			assertThat(captor.getValue().destinationKey()).isEqualTo(publicKey);
			// 확정본은 심사·콘솔 상세가 계속 읽는다 — 복사가 원본을 지우지 않는다.
			then(s3Client).should(never()).deleteObject(any(DeleteObjectRequest.class));
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("승인이 롤백되면 방금 만든 공개 사본이 정리된다 — 아무도 참조하지 않는 고아를 남기지 않는다")
		void 승인이_롤백되면_공개_사본이_정리된다() {
			업로드된_객체가_있다(1024L);
			String publicKey = imageStore.copyToMissionImage(ORIGINAL_KEY);

			TransactionSynchronizationUtils.invokeAfterCompletion(
				TransactionSynchronizationManager.getSynchronizations(),
				TransactionSynchronization.STATUS_ROLLED_BACK);

			ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
			then(s3Client).should().deleteObject(captor.capture());
			assertThat(captor.getValue().key()).isEqualTo(publicKey);
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("커밋되면 공개 사본을 지우지 않는다 — 미션 카드가 그 주소를 읽는다")
		void 커밋되면_공개_사본을_지우지_않는다() {
			업로드된_객체가_있다(1024L);
			imageStore.copyToMissionImage(ORIGINAL_KEY);

			TransactionSynchronizationUtils.invokeAfterCompletion(
				TransactionSynchronizationManager.getSynchronizations(),
				TransactionSynchronization.STATUS_COMMITTED);

			then(s3Client).should(never()).deleteObject(any(DeleteObjectRequest.class));
		}
	}
}
