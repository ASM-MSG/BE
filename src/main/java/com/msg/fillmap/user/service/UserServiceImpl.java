package com.msg.fillmap.user.service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.service.RefreshTokenService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 계정 삭제 (MSG-205). DB 연쇄는 FK CASCADE 전담(보정 로직 없음 — 행이 통째로 사라지므로 무의미),
 * 유일한 실질 신규 로직은 영상 S3 객체 일괄 정리다. RefreshTokenService·TokenProvider 주입은
 * user→auth 의존이지만 둘 다 Owner B 내부 — 도메인 간 계약 인터페이스(A↔B) 규칙과 무관.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

	// DeleteObjects 요청당 키 상한 (S3 API 제한) — 넘으면 청크로 나눈다 (§S3 정리).
	private static final int S3_DELETE_CHUNK_SIZE = 1000;

	private final UserRepository userRepository;
	private final RefreshTokenService refreshTokenService;
	private final TokenProvider tokenProvider;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;

	@Override
	@Transactional
	public void deleteAccount(Long userId, String accessToken) {
		// 삭제 전에 수집한다 — DELETE 가 CASCADE 로 videos 행을 지우면 키의 접근 경로가 사라진다.
		// status DELETED(soft) 행도 전량 포함 — 없는 키는 DeleteObjects 가 에러 없이 넘어가므로 무해.
		List<String> s3Keys = collectS3Keys(userId);
		if (userRepository.deleteUser(userId) == 0) {
			// 이미 없는 유저 — 멱등 성공은 블랙리스트 결함(정상이면 재호출 자체가 401)을 숨기므로 배제 (§D3).
			throw new ApiException(UserErrorCode.USER_NOT_FOUND);
		}
		// 커밋 이후 정리(S3·Redis 공통) 실패를 500 으로 응답하지 않는다 — 이미 커밋된 삭제가
		// "실패"로 보이는 역전을 막는다 (VideoServiceImpl.deleteQuietly 주석의 논리 그대로).
		afterCommit(() -> {
			deleteS3Objects(s3Keys);
			invalidateSessions(userId, accessToken);
		});
	}

	/** 4컬럼(원본·인코딩본·썸네일·블러본 — 컬럼명과 달리 전부 실제 S3 key) 평탄화. null 은 미생성 키다. */
	private List<String> collectS3Keys(Long userId) {
		return userRepository.findAllS3KeysByUserId(userId).stream()
			.flatMap(Arrays::stream)
			.filter(Objects::nonNull)
			.map(String.class::cast)
			.toList();
	}

	/**
	 * 수집 키 일괄 삭제 — 1000키/요청 청크 (§S3 정리). VideoServiceImpl.deleteQuietly 와 동일 구조지만
	 * 재사용하지 않고 자체 구현한다(§D-재사용) — 공용 추출은 세 번째 사용처가 생기면 그때.
	 */
	private void deleteS3Objects(List<String> keys) {
		for (int i = 0; i < keys.size(); i += S3_DELETE_CHUNK_SIZE) {
			deleteChunk(keys.subList(i, Math.min(i + S3_DELETE_CHUNK_SIZE, keys.size())));
		}
	}

	/**
	 * DeleteObjects 는 배치 API 라 개별 객체 실패를 예외로 던지지 않는다 — HTTP 200 에 errors 를 담아
	 * 돌려주므로 응답을 봐야 한다. 실패는 비식별 고아 객체(비용·위생 문제)일 뿐이라 로그만 남기고 삼킨다.
	 */
	private void deleteChunk(List<String> keys) {
		List<ObjectIdentifier> targets = keys.stream()
			.map(key -> ObjectIdentifier.builder().key(key).build())
			.toList();
		try {
			DeleteObjectsResponse response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.delete(Delete.builder().objects(targets).build())
				.build());
			if (response.hasErrors()) {
				log.error("계정 삭제 S3 객체 정리 실패 — 고아로 남는다: {}", response.errors());
			}
		} catch (SdkException e) {
			log.error("계정 삭제 S3 객체 정리 호출 실패 — 고아로 남는다: keys={}", targets, e);
		}
	}

	/**
	 * 전 디바이스 refresh 소멸(SCAN 기반 deleteAll) + 요청에 쓴 액세스 토큰 블랙리스트 (FR-4).
	 * 각각 독립 try-catch — 한쪽 실패가 다른 쪽 시도를 막으면 안 된다 (Codex 리뷰 반영).
	 * 실패는 로그만 — refresh 는 TTL(2주)로, 액세스 토큰은 만료로 자연 소멸이 안전망.
	 */
	private void invalidateSessions(Long userId, String accessToken) {
		try {
			refreshTokenService.deleteAll(userId);
		} catch (RuntimeException e) {
			log.error("계정 삭제 refresh 세션 정리 실패 — TTL 만료가 안전망이다: userId={}", userId, e);
		}
		try {
			tokenProvider.invalidateAccessToken(accessToken);
		} catch (RuntimeException e) {
			log.error("계정 삭제 액세스 토큰 블랙리스트 실패 — 토큰 만료가 안전망이다: userId={}", userId, e);
		}
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
}
