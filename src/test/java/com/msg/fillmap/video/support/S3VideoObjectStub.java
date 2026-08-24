package com.msg.fillmap.video.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * 업로드 확정이 보는 pending 객체 목 (MSG-392) — "정상 업로드를 마친 상태"를 만드는 스텁이다.
 * 확정이 존재(headObject)와 내용(앞부분 범위 요청) 둘 다 보므로 스텁도 함께 세운다. headObject 만 세우면
 * 범위 요청이 null 을 돌려받아 NPE 로 죽는데, 컴파일은 통과하므로 빌드 시점에 드러나지 않는다.
 *
 * contentLength 를 스텁 바이트 길이와 맞추는 것이 계약이다 — 확정이 그 값으로 파일 끝을 판정해서,
 * 안 맞추면 정상 스텁이 잘린 구조로 보여 3428 로 떨어진다.
 */
public final class S3VideoObjectStub {

	/**
	 * 크기 16을 선언한 ftyp 박스 하나 — 선언 크기와 실제 길이가 맞는 가장 짧은 통과 입력이다.
	 * 브랜드(isom)는 판별에 쓰이지 않으므로 값 자체에 의미는 없다. 실제 영상 파일을 리소스로 두지 않는다.
	 */
	private static final byte[] MINIMAL_MP4_HEAD = {
		0x00, 0x00, 0x00, 0x10, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0x00, 0x00, 0x00, 0x00};

	private S3VideoObjectStub() {
	}

	public static void givenUploadedVideoObject(S3Client s3Client) {
		givenUploadedVideoObject(s3Client, MINIMAL_MP4_HEAD);
	}

	/** 내용이 다른 객체를 세울 때 쓴다 — contentLength 는 준 바이트 길이로 함께 맞춘다. */
	public static void givenUploadedVideoObject(S3Client s3Client, byte[] objectBytes) {
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength((long) objectBytes.length).build());
		given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
			.willReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), objectBytes));
	}
}
