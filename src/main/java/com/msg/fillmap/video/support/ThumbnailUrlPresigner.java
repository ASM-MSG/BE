package com.msg.fillmap.video.support;

import java.time.Duration;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import com.msg.fillmap.global.config.AwsProperties;

/**
 * 썸네일 S3 key → TTL 있는 presigned GET URL 발급 (MSG-127 에서 추출, MSG-153 D4).
 * 버킷이 private 이라 key 는 그대로 열람 불가라 요청 시점에 서명한다. key 가 null(READY 이전)이면
 * 발급 대상이 없어 null 을 반환한다 — 소비처가 processingStatus 로 플레이스홀더를 그린다.
 * presign 은 로컬 서명 연산(S3 호출 아님)이라 항목마다 발급해도 N+1 우려가 없다.
 * video(격자별 영상·전역 대표 영상)·usergrid(도감 갤러리 목록) 두 소비처가 공유한다(둘 다 Owner B).
 */
@Component
@RequiredArgsConstructor
public class ThumbnailUrlPresigner {

	// 썸네일 열람용 GET presign TTL (MSG-127). 목록 열람 세션 내 유효하면 충분하다.
	private static final Duration TTL = Duration.ofMinutes(10);

	private final S3Presigner s3Presigner;
	private final AwsProperties awsProperties;

	/** presign 이 발급하는 URL 의 TTL(초). 재생 조회(MSG-206)가 expiresInSec 로 노출한다. */
	public long ttlSeconds() {
		return TTL.toSeconds();
	}

	public String presign(String thumbnailKey) {
		if (thumbnailKey == null) {
			return null;
		}
		GetObjectRequest objectRequest = GetObjectRequest.builder()
			.bucket(awsProperties.s3().bucket())
			.key(thumbnailKey)
			.build();
		return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
			.signatureDuration(TTL)
			.getObjectRequest(objectRequest)
			.build()).url().toString();
	}
}
