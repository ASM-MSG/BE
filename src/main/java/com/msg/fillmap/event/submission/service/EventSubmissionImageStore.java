package com.msg.fillmap.event.submission.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignResponseDto;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 신청 대표 이미지의 2단 업로드 (MSG-498 §대표 이미지 확정). 프로필 이미지(MSG-373) 흐름을 미러링하되
 * 두 가지가 다르다 — webp 를 받지 않고(시안 문구가 "JPG 또는 PNG"다), 확정본 프리픽스에 공개 읽기를 열지
 * 않는다(열람자가 신청 소유자와 관리자뿐이라 presigned GET 으로 충분하다).
 * <p>
 * 확정 키의 uuid 는 복사 시점에 새로 발급한다 — 신청마다 독립 객체를 소유하므로 두 신청이 같은 객체를
 * 공유하는 상태 자체가 성립하지 않고, 재제출의 이전 이미지 삭제가 다른 신청의 이미지에 닿을 경로가 없다.
 * 복사에 성공한 pending 은 커밋 후 지운다 — 같은 pending 키로 두 번 신청하는 경로를 막기 위해서다
 * (재확정 시도는 HEAD 실패라 13436 이 된다). 이 삭제는 정리 목적 베스트 에포트라 실패해도 신청은 유효하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventSubmissionImageStore {

	/**
	 * 허용 확장자 → 정규 Content-Type. 쌍으로 검증해 엇갈린 조합을 막는다 (프로필 이미지 선례).
	 * 이 맵 하나가 발급 검증과 확정 최종 관문 양쪽의 화이트리스트다.
	 */
	private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
		"jpg", "image/jpeg",
		"jpeg", "image/jpeg",
		"png", "image/png");

	private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);

	/** 시안이 고정한 값이라 설정화하지 않는다 — 바꾸는 일 자체가 PRD 개정이다 (프로필 이미지 §D-6 과 같은 결). */
	private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

	private static final String PENDING_PREFIX = "event-submissions/pending/";
	private static final String ORIGINAL_PREFIX = "event-submissions/original/";

	private final S3Presigner s3Presigner;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;
	private final ThumbnailUrlPresigner thumbnailUrlPresigner;

	public EventSubmissionImagePresignResponseDto presign(Long userId,
		EventSubmissionImagePresignRequestDto request) {
		String extension = request.extension().toLowerCase();
		String allowedType = ALLOWED_IMAGE_TYPES.get(extension);
		if (allowedType == null || !allowedType.equals(request.contentType())) {
			throw new ApiException(EventErrorCode.SUBMISSION_IMAGE_UNSUPPORTED);
		}
		if (request.contentLength() > MAX_IMAGE_BYTES) {
			throw new ApiException(EventErrorCode.SUBMISSION_IMAGE_TOO_LARGE);
		}

		String s3Key = "%s%d/%s.%s".formatted(PENDING_PREFIX, userId, UUID.randomUUID(), extension);

		// contentLength·contentType 을 서명에 포함시켜 클라이언트가 선언과 다른 크기·타입으로 올리면 S3 가
		// 403 을 낸다 (PUT presign 에는 POST policy 의 content-length-range 같은 범위 조건이 없다).
		PutObjectRequest objectRequest = PutObjectRequest.builder()
			.bucket(awsProperties.s3().bucket())
			.key(s3Key)
			.contentType(request.contentType())
			.contentLength(request.contentLength())
			.build();

		String uploadUrl = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
			.signatureDuration(PRESIGN_TTL)
			.putObjectRequest(objectRequest)
			.build()).url().toString();

		return new EventSubmissionImagePresignResponseDto(uploadUrl, s3Key, PRESIGN_TTL.toSeconds());
	}

	/**
	 * pending 키를 확정본으로 복사하고 저장할 키를 돌려준다. 검증은 S3 를 건드리지 않는 것부터 —
	 * 키 형식 → 실존(HeadObject) → 실측 크기 순이라 잘못된 요청이 S3 부수효과 없이 거부된다.
	 * <p>
	 * 소유(prefix) 검사만으로는 부족하다 — 신청자는 자기 userId 를 아니까 지어낸 키를 보낼 수 있고 실존
	 * 확인까지 있어야 막힌다. 실측 0바이트를 없는 것과 같이 취급하는 이유는 빈 객체가 대표 이미지로 저장되면
	 * 심사 화면이 깨진 이미지를 그리게 되기 때문이다.
	 */
	public String confirm(Long userId, String pendingKey) {
		String extension = validatePendingKey(userId, pendingKey);
		HeadObjectResponse head = requireObjectExists(pendingKey);
		Long contentLength = head.contentLength();
		if (contentLength == null || contentLength == 0L) {
			throw new ApiException(EventErrorCode.SUBMISSION_IMAGE_NOT_UPLOADED);
		}
		if (contentLength > MAX_IMAGE_BYTES) {
			throw new ApiException(EventErrorCode.SUBMISSION_IMAGE_TOO_LARGE);
		}

		String originalKey = "%s%d/%s.%s".formatted(ORIGINAL_PREFIX, userId, UUID.randomUUID(), extension);
		s3Client.copyObject(CopyObjectRequest.builder()
			.sourceBucket(awsProperties.s3().bucket())
			.sourceKey(pendingKey)
			.destinationBucket(awsProperties.s3().bucket())
			.destinationKey(originalKey)
			.build());
		deleteOnRollback(originalKey);
		afterCommit(() -> deleteQuietly(pendingKey));
		return originalKey;
	}

	/** 열람용 presigned GET (§API 4). 발급 방식·TTL 은 썸네일 선례 상수를 그대로 쓴다. */
	public String presignGet(String imageKey) {
		return thumbnailUrlPresigner.presign(imageKey);
	}

	/** 재제출로 밀려난 이전 확정 이미지 정리 — 커밋 후 베스트 에포트다 (프로필 이미지 선례). */
	public void deleteAfterCommit(String imageKey) {
		if (imageKey != null) {
			afterCommit(() -> deleteQuietly(imageKey));
		}
	}

	private String validatePendingKey(Long userId, String pendingKey) {
		if (!pendingKey.startsWith("%s%d/".formatted(PENDING_PREFIX, userId))) {
			throw new ApiException(EventErrorCode.SUBMISSION_IMAGE_KEY_INVALID);
		}
		int extensionAt = pendingKey.lastIndexOf('.');
		if (extensionAt < 0) {
			throw new ApiException(EventErrorCode.SUBMISSION_IMAGE_KEY_INVALID);
		}
		String extension = pendingKey.substring(extensionAt + 1).toLowerCase();
		if (!ALLOWED_IMAGE_TYPES.containsKey(extension)) {
			throw new ApiException(EventErrorCode.SUBMISSION_IMAGE_KEY_INVALID);
		}
		return extension;
	}

	private HeadObjectResponse requireObjectExists(String s3Key) {
		try {
			return s3Client.headObject(HeadObjectRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.key(s3Key)
				.build());
		} catch (NoSuchKeyException e) {
			throw new ApiException(EventErrorCode.SUBMISSION_IMAGE_NOT_UPLOADED, e);
		} catch (S3Exception e) {
			// HeadObject 는 본문 없는 404 를 주므로 SDK 가 NoSuchKeyException 으로 못 좁히는 경우가 있다.
			if (e.statusCode() == 404) {
				throw new ApiException(EventErrorCode.SUBMISSION_IMAGE_NOT_UPLOADED, e);
			}
			throw e;
		}
	}

	/**
	 * 복사 이후 트랜잭션이 롤백되면 방금 만든 확정본이 아무도 참조하지 않는 고아로 남는다 —
	 * {@code VideoServiceImpl.deleteOnRollback}(MSG-247) 선례 그대로 보상 삭제한다.
	 * STATUS_UNKNOWN(커밋 결과 불명)은 남긴다 — 커밋됐을 수 있는 이미지를 지우면 유실이고 고아는 비용 문제뿐이다.
	 * 확정 키가 시도마다 새 uuid 라 목적지 공유가 없어, 이 보상이 다른 시도의 객체를 지울 수 없다.
	 */
	private void deleteOnRollback(String originalKey) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
					deleteQuietly(originalKey);
				}
			}
		});
	}

	/** 트랜잭션이 없으면(단위 테스트 등) 그냥 지금 실행한다 — VideoServiceImpl.afterCommit 패턴. */
	private void afterCommit(Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			action.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}

	private void deleteQuietly(String s3Key) {
		try {
			s3Client.deleteObject(DeleteObjectRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.key(s3Key)
				.build());
		} catch (SdkException e) {
			log.error("신청 이미지 정리 실패 — 고아로 남는다: key={}", s3Key, e);
		}
	}
}
