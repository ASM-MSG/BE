package com.msg.fillmap.video.support;

import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.config.CloudFrontProperties;

/**
 * 미디어 S3 key → TTL 있는 signed GET URL 발급 (MSG-127 에서 추출, MSG-153 D4, MSG-67).
 * 버킷이 private 이라 key 는 그대로 열람 불가라 요청 시점에 서명한다. key 가 null(READY 이전)이면
 * 발급 대상이 없어 null 을 반환한다 — 소비처가 processingStatus 로 플레이스홀더를 그린다.
 * 서명은 로컬 연산(S3·CloudFront 호출 아님)이라 항목마다 발급해도 N+1 우려가 없다.
 * video(격자별 영상·전역 대표 영상)·usergrid(도감 갤러리 목록) 두 소비처가 공유한다(둘 다 Owner B).
 */
@Component
public class ThumbnailUrlPresigner {

	// 썸네일 열람용 GET presign TTL (MSG-127). 목록 열람 세션 내 유효하면 충분하다.
	private static final Duration TTL = Duration.ofMinutes(10);

	private final S3Presigner s3Presigner;
	private final AwsProperties awsProperties;
	private final CloudFrontProperties cloudFrontProperties;
	private final CloudFrontUtilities cloudFrontUtilities;
	private final PrivateKey cloudFrontPrivateKey;

	/** 기존 단위 테스트와 CloudFront 비활성 환경은 S3 서명을 그대로 쓴다. */
	public ThumbnailUrlPresigner(S3Presigner s3Presigner, AwsProperties awsProperties) {
		this(s3Presigner, awsProperties, new CloudFrontProperties(false, null, null, null));
	}

	@Autowired
	public ThumbnailUrlPresigner(
		S3Presigner s3Presigner,
		AwsProperties awsProperties,
		CloudFrontProperties cloudFrontProperties
	) {
		this.s3Presigner = s3Presigner;
		this.awsProperties = awsProperties;
		this.cloudFrontProperties = cloudFrontProperties;
		this.cloudFrontUtilities = CloudFrontUtilities.create();
		this.cloudFrontPrivateKey = loadCloudFrontPrivateKey(cloudFrontProperties);
	}

	/** presign 이 발급하는 URL 의 TTL(초). 재생 조회(MSG-206)가 expiresInSec 로 노출한다. */
	public long ttlSeconds() {
		return TTL.toSeconds();
	}

	public String presign(String mediaKey) {
		if (mediaKey == null) {
			return null;
		}
		if (cloudFrontProperties.enabled()) {
			return cloudFrontUtilities.getSignedUrlWithCannedPolicy(CannedSignerRequest.builder()
				.resourceUrl("https://%s/%s".formatted(cloudFrontProperties.domain(), mediaKey))
				.privateKey(cloudFrontPrivateKey)
				.keyPairId(cloudFrontProperties.keyPairId())
				.expirationDate(Instant.now().plus(TTL))
				.build()).url();
		}
		GetObjectRequest objectRequest = GetObjectRequest.builder()
			.bucket(awsProperties.s3().bucket())
			.key(mediaKey)
			.build();
		return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
			.signatureDuration(TTL)
			.getObjectRequest(objectRequest)
			.build()).url().toString();
	}

	private PrivateKey loadCloudFrontPrivateKey(CloudFrontProperties properties) {
		if (!properties.enabled()) {
			return null;
		}
		if (!StringUtils.hasText(properties.domain()) || !StringUtils.hasText(properties.keyPairId())
			|| properties.privateKeyPath() == null) {
			throw new IllegalStateException("CloudFront 서명 URL 필수 설정이 누락됐습니다");
		}
		try {
			return CannedSignerRequest.builder()
				.resourceUrl("https://" + properties.domain())
				.privateKey(properties.privateKeyPath())
				.keyPairId(properties.keyPairId())
				.expirationDate(Instant.now().plus(TTL))
				.build()
				.privateKey();
		} catch (Exception e) {
			throw new IllegalStateException("CloudFront 개인 키를 읽을 수 없습니다", e);
		}
	}
}
