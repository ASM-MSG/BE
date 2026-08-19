package com.msg.fillmap.user.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.service.RefreshTokenService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.ConsentStatusResponseDto;
import com.msg.fillmap.user.dto.ConsentSubmitRequestDto;
import com.msg.fillmap.user.dto.ProfileImagePresignRequestDto;
import com.msg.fillmap.user.dto.ProfileImagePresignResponseDto;
import com.msg.fillmap.user.dto.UserProfileResponseDto;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 계정 삭제 (MSG-205). DB 연쇄는 FK CASCADE 전담(보정 로직 없음 — 행이 통째로 사라지므로 무의미),
 * 유일한 실질 신규 로직은 영상 S3 객체 일괄 정리다. RefreshTokenService·TokenProvider 주입은
 * user→auth 의존이지만 둘 다 Owner B 내부 — 도메인 간 계약 인터페이스(A↔B) 규칙과 무관.
 *
 * 프로필 이미지 (MSG-373): presign 발급·확정은 영상(MSG-64·132·133)의 패턴만 가져오고 코드는
 * 재사용하지 않는다 — 영상 쪽 구현은 VideoServiceImpl private 이고 DTO 도 영상 전용이다(§D-3).
 * 공용 컴포넌트 추출은 세 번째 사용처가 생기면 그때 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

	// DeleteObjects 요청당 키 상한 (S3 API 제한) — 넘으면 청크로 나눈다 (§S3 정리).
	private static final int S3_DELETE_CHUNK_SIZE = 1000;

	// 허용 확장자 → 정규 Content-Type (MSG-373). 쌍으로 검증해 엇갈린 조합을 막는다 (영상 MSG-64 D2 미러).
	// jpeg 는 jpg 와 같은 형식의 표기 차이라 함께 받는다. heic·heif 는 받지 않는다 — 저장해도 대부분의
	// 브라우저가 표시하지 못해 "저장은 됐는데 안 보이는" 상태가 되기 때문이다. 아이폰 사진은 클라이언트가
	// 변환해 올린다(iOS 사진 선택기가 플랫폼 수준에서 JPEG 로 준다 — 2026-08-11 재확정 §D-4).
	// 이 맵 하나가 발급 검증과 확정 최종 관문 양쪽의 화이트리스트라 형식 목록 변경 지점이 여기 하나다.
	private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
		"jpg", "image/jpeg",
		"jpeg", "image/jpeg",
		"png", "image/png",
		"webp", "image/webp");

	private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);

	// 제품 요구사항이 고정한 값이라 설정화하지 않는다 — 바꾸는 일 자체가 PRD 개정이다 (§D-6).
	private static final long MAX_PROFILE_IMAGE_BYTES = 5L * 1024 * 1024;

	// 영상 키 경로 미러링 (§D-2). pending 만 라이프사이클 만료 대상이고, 공개 읽기는 original 프리픽스만 연다.
	private static final String PROFILE_PENDING_PREFIX = "profiles/pending/";
	private static final String PROFILE_ORIGINAL_PREFIX = "profiles/original/";

	private final UserRepository userRepository;
	private final RefreshTokenService refreshTokenService;
	private final TokenProvider tokenProvider;
	private final S3Presigner s3Presigner;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — 마지막 인자 clock 을 Clock.systemUTC() 로 고정해 Lombok 전체 생성자로 위임한다
	 * (BadgeAwardServiceImpl 선례). 위치정보·가입 약관 동의의 시각 산출에만 쓰며, 전체 생성자는 테스트
	 * 고정 클럭 주입용이다.
	 */
	@Autowired
	public UserServiceImpl(UserRepository userRepository, RefreshTokenService refreshTokenService,
		TokenProvider tokenProvider, S3Presigner s3Presigner, S3Client s3Client, AwsProperties awsProperties) {
		this(userRepository, refreshTokenService, tokenProvider, s3Presigner, s3Client, awsProperties,
			Clock.systemUTC());
	}

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

	@Override
	@Transactional(readOnly = true)
	public UserProfileResponseDto getMyProfile(Long userId) {
		return UserProfileResponseDto.from(findUser(userId));
	}

	@Override
	@Transactional
	public UserProfileResponseDto updateNickname(Long userId, String nickname) {
		User user = findUser(userId);
		user.updateNickname(nickname);
		return UserProfileResponseDto.from(user);
	}

	@Override
	public ProfileImagePresignResponseDto issueProfileImagePresignedUrl(
		Long userId, ProfileImagePresignRequestDto request) {
		String extension = request.extension().toLowerCase();
		String allowedType = ALLOWED_IMAGE_TYPES.get(extension);
		if (allowedType == null || !allowedType.equals(request.contentType())) {
			throw new ApiException(UserErrorCode.UNSUPPORTED_IMAGE_EXTENSION);
		}
		if (request.contentLength() > MAX_PROFILE_IMAGE_BYTES) {
			throw new ApiException(UserErrorCode.IMAGE_TOO_LARGE);
		}

		String s3Key = "%s%d/%s.%s".formatted(PROFILE_PENDING_PREFIX, userId, UUID.randomUUID(), extension);

		// contentLength/contentType 을 서명에 포함시켜 클라이언트가 선언과 다른 크기·타입으로 올리면 S3 가 403 을 낸다
		// (PUT presign 에는 POST policy 의 content-length-range 같은 범위 조건이 없다 — 영상 MSG-64 D3 그대로).
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

		return new ProfileImagePresignResponseDto(uploadUrl, s3Key, PRESIGN_TTL.toSeconds());
	}

	/**
	 * 변경 확정 (MSG-373 §API 2). 검증은 S3 를 건드리지 않는 것부터 — 키 형식 → 유저 조회 → 실존(HeadObject)
	 * → 실측 크기 순이라 잘못된 요청이 S3 호출이나 부수효과 없이 거부된다.
	 *
	 * 소유권(prefix)만으로는 부족하다 — 공격자는 자기 userId 를 아니까 지어낸 키를 보낼 수 있고, 실존
	 * 확인까지 있어야 막힌다(영상 confirmUpload 의 논리). 실측 크기 재검증은 presign 을 우회해 직접 올린
	 * 큰 파일을 확정 시점에 잡는다 — 존재 확인에 이미 쓴 응답을 재사용하므로 추가 S3 호출이 없다.
	 *
	 * 영상과 달리 advisory lock·키 이중 사용 차단·복사 롤백 보상은 걸지 않는다 (§D-3). 같은 키를 두 번
	 * 확정해도 결과가 같은 URL 저장이고, 최악의 결과가 고아 객체 한 개(5MB 이하)뿐이라 도메인 불변식이
	 * 깨지지 않는다.
	 */
	@Override
	@Transactional
	public UserProfileResponseDto updateProfileImage(Long userId, String pendingKey) {
		String extension = validatePendingKey(userId, pendingKey);
		User user = findUser(userId);
		HeadObjectResponse head = requireObjectExists(pendingKey);
		Long contentLength = head.contentLength();
		if (contentLength != null && contentLength > MAX_PROFILE_IMAGE_BYTES) {
			throw new ApiException(UserErrorCode.IMAGE_TOO_LARGE);
		}

		// 확정마다 새 UUID — URL 이 바뀌어 브라우저 캐시가 자연히 무효화된다.
		String originalKey = "%s%d/%s.%s".formatted(PROFILE_ORIGINAL_PREFIX, userId, UUID.randomUUID(), extension);
		copyToOriginal(pendingKey, originalKey);

		String replacedUrl = user.getProfileImageUrl();
		user.changeProfileImage(toPublicUrl(originalKey));
		// pending 원본은 지우지 않는다 — 라이프사이클이 쓸어간다 (영상 선례 그대로).
		afterCommit(() -> deleteProfileImageObject(replacedUrl));
		return UserProfileResponseDto.from(user);
	}

	@Override
	@Transactional
	public UserProfileResponseDto removeProfileImage(Long userId) {
		User user = findUser(userId);
		String replacedUrl = user.getProfileImageUrl();
		user.changeProfileImage(null);
		// 이미 기본 상태면 replacedUrl 이 null 이라 삭제 호출 자체가 없다 (멱등 성공).
		afterCommit(() -> deleteProfileImageObject(replacedUrl));
		return UserProfileResponseDto.from(user);
	}

	/**
	 * 위치정보 사용 동의 변경 (MSG-402 §도메인 로직). 갱신은 원자 UPDATE 한 문장이고(§D-4) 값 비교도
	 * 그 안에서 끝나므로 여기에 "지금 값이 뭐냐"를 읽는 코드가 없다. @Transactional 은 필수다 —
	 * @Modifying 쿼리는 트랜잭션 없이 실행이 거부된다.
	 *
	 * 시각은 주입된 Clock 으로 UTC 에서 만들고 DB 정밀도(µs)로 절단한다 (§D-5, User.createdAt 선례).
	 * 같은 값 재저장이면 이 값이 쓰이지 않고 기존 시각이 남는다.
	 *
	 * <p><b>철회 불가</b> (2026-08-19 팀 합의, FR-USER-14 개정) — 이 API 는 켜기 전용이다. false 요청은
	 * 1400 으로 거절되므로 저장된 값이 true 에서 false 로 되돌아갈 경로가 없고, 가입 게이트의 필수 동의
	 * 완료가 사후에 풀리는 일도 없다 (MSG-433 §D-11). 켜기 재요청은 그대로 멱등 성공이다 —
	 * 웹 온보딩(MSG-407)이 이 경로로 동의를 켠다.
	 *
	 * <p><b>수용한 경합</b> — 시각을 UPDATE 문 밖에서 미리 뽑으므로, 같은 사용자의 켜기 요청 두 개가
	 * 밀리초 간격으로 겹치면 저장된 시각이 실제 순서와 어긋날 수 있다. 요청 A 가 t1 을 뽑고 행 잠금을
	 * 기다리는 사이 요청 B 가 t2(더 나중)를 저장하고, 뒤늦게 잠금을 잡은 A 가 t1 을 기록하는 경우다.
	 * 동의 여부(location_consent) 자체는 항상 마지막 UPDATE 값이라 정확하고, 어긋나는 것은 변경 시각
	 * 하나이며 그 폭도 두 요청이 시각을 뽑은 간격 수준이다. 이 컬럼의 목적이 "언제쯤 동의했나"에
	 * 답하는 법적 보관이라 밀리초 오차가 의미를 바꾸지 않아 수용한다. DB 의 now() 로 옮기면 문장 안에서
	 * 순서가 맞지만, 컨테이너 시간대(KST)의 벽시계가 들어와 UTC 저장 컨벤션(MSG-376)을 깬다.
	 */
	@Override
	@Transactional
	public UserProfileResponseDto updateLocationConsent(Long userId, boolean consented) {
		if (!consented) {
			throw new ApiException(UserErrorCode.LOCATION_CONSENT_IRREVOCABLE);
		}
		LocalDateTime changedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
		if (userRepository.updateLocationConsent(userId, consented, changedAt) == 0) {
			throw new ApiException(UserErrorCode.USER_NOT_FOUND);
		}
		// clearAutomatically 가 갱신 전 스냅숏을 비운 뒤라 이 재조회는 DB 의 저장 값을 읽는다.
		return UserProfileResponseDto.from(findUser(userId));
	}

	@Override
	@Transactional(readOnly = true)
	public ConsentStatusResponseDto getConsentStatus(Long userId) {
		return ConsentStatusResponseDto.from(findUser(userId));
	}

	/**
	 * 가입 약관 동의 제출 (MSG-433 §도메인 로직). 필수 4항목은 요청 DTO 의 @NotNull·@AssertTrue 가
	 * 컨트롤러 진입 전에 걸렀으므로 여기 도달했다는 것이 곧 전량 동의라, 리포지토리에 넘기는 값은
	 * 마케팅 여부와 시각뿐이다 (§D-5·D-6). 판정·비교는 전부 UPDATE 문 안에서 끝난다.
	 *
	 * 시각은 updateLocationConsent 와 같은 방식으로 주입된 Clock 에서 UTC 로 만들고 DB 정밀도(µs)로
	 * 절단한다 (§D-10). 시각 샘플이 행 잠금보다 앞서는 데서 오는 경합 수용도 그 메서드의 판정을
	 * 그대로 승계한다 — 동의 여부 자체는 항상 마지막 UPDATE 값이라 정확하다.
	 */
	@Override
	@Transactional
	public ConsentStatusResponseDto submitConsents(Long userId, ConsentSubmitRequestDto request) {
		LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
		if (userRepository.submitConsents(userId, request.marketing(), now) == 0) {
			throw new ApiException(UserErrorCode.USER_NOT_FOUND);
		}
		return ConsentStatusResponseDto.from(findUser(userId));
	}

	/** 마케팅 수신 동의 변경 (MSG-433 §D-4) — updateLocationConsent 와 완전 동형이다. */
	@Override
	@Transactional
	public ConsentStatusResponseDto updateMarketingConsent(Long userId, boolean consented) {
		LocalDateTime changedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
		if (userRepository.updateMarketingConsent(userId, consented, changedAt) == 0) {
			throw new ApiException(UserErrorCode.USER_NOT_FOUND);
		}
		return ConsentStatusResponseDto.from(findUser(userId));
	}

	/**
	 * 내 pending 경로 아래의 허용 확장자 키인지 확인하고 확장자를 돌려준다. 아니면 1401.
	 *
	 * 확장자 화이트리스트를 확정에서 한 번 더 본다(스펙 §API 2 의 "확장자를 가졌는지"보다 엄격). 확정본
	 * 프리픽스는 공개 읽기라 svg·html 이 올라가면 그 S3 도메인에서 스크립트가 실행된다 — presign 이
	 * 허용 형식만 서명하므로 그런 객체가 만들어질 경로는 없지만, 공개 경로로 가는 마지막 관문이라
	 * prefix·실존 검사와 같은 자리에 둔다.
	 */
	private String validatePendingKey(Long userId, String pendingKey) {
		if (!pendingKey.startsWith("%s%d/".formatted(PROFILE_PENDING_PREFIX, userId))) {
			throw new ApiException(UserErrorCode.INVALID_IMAGE_KEY);
		}
		int extAt = pendingKey.lastIndexOf('.');
		if (extAt < 0) {
			throw new ApiException(UserErrorCode.INVALID_IMAGE_KEY);   // presign 발급 키는 항상 확장자를 가진다
		}
		String extension = pendingKey.substring(extAt + 1).toLowerCase();
		if (!ALLOWED_IMAGE_TYPES.containsKey(extension)) {
			throw new ApiException(UserErrorCode.INVALID_IMAGE_KEY);
		}
		return extension;
	}

	/**
	 * pending → original 서버측 복사. 실패하면 예외가 전파돼 트랜잭션이 롤백되고 pending 은 만료된다.
	 * 복사 성공 후 롤백되면 original 쪽 고아가 남는데, 롤백 보상을 걸지 않는 이유는 §D-3 참조.
	 */
	private void copyToOriginal(String pendingKey, String originalKey) {
		s3Client.copyObject(CopyObjectRequest.builder()
			.sourceBucket(awsProperties.s3().bucket())
			.sourceKey(pendingKey)
			.destinationBucket(awsProperties.s3().bucket())
			.destinationKey(originalKey)
			.build());
	}

	private HeadObjectResponse requireObjectExists(String s3Key) {
		try {
			return s3Client.headObject(HeadObjectRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.key(s3Key)
				.build());
		} catch (NoSuchKeyException e) {
			throw new ApiException(UserErrorCode.IMAGE_UPLOAD_NOT_FOUND, e);
		} catch (S3Exception e) {
			// HeadObject 는 본문 없는 404 를 주므로 SDK 가 NoSuchKeyException 으로 못 좁히는 경우가 있다.
			if (e.statusCode() == 404) {
				throw new ApiException(UserErrorCode.IMAGE_UPLOAD_NOT_FOUND, e);
			}
			throw e;
		}
	}

	/**
	 * 저장 값은 S3 키가 아니라 그대로 열리는 공개 URL 이다 (§D-1) — 친구 축 응답 3종의 프로젝션이 이 컬럼
	 * 원문을 그대로 싣기 때문에 코드 변경 없이 반영되려면 이 형태여야 한다. CloudFront 도입 시 이 메서드와
	 * 짝인 toS3Key 만 교체하면 된다.
	 */
	private String toPublicUrl(String s3Key) {
		return "https://%s.s3.%s.amazonaws.com/%s".formatted(
			awsProperties.s3().bucket(), awsProperties.region(), s3Key);
	}

	/** toPublicUrl 의 역산 (정리용). 우리가 만든 형태가 아니면 지울 키를 모르니 null 이다. */
	private String toS3Key(String publicUrl) {
		String base = toPublicUrl("");
		return publicUrl != null && publicUrl.startsWith(base) ? publicUrl.substring(base.length()) : null;
	}

	/** 교체·제거로 밀려난 이전 이미지 정리 — 커밋 후 best-effort (§D-5). 미설정이면 호출 자체가 없다. */
	private void deleteProfileImageObject(String publicUrl) {
		String s3Key = toS3Key(publicUrl);
		if (s3Key != null) {
			deleteS3Objects(List.of(s3Key));
		}
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
	}

	/**
	 * 영상 4컬럼(원본·인코딩본·썸네일·블러본 — 컬럼명과 달리 전부 실제 S3 key) 평탄화에 프로필 이미지
	 * 키를 더한다 (MSG-373 §D-5). null 은 미생성 키다.
	 */
	private List<String> collectS3Keys(Long userId) {
		List<String> keys = new ArrayList<>(userRepository.findAllS3KeysByUserId(userId).stream()
			.flatMap(Arrays::stream)
			.filter(Objects::nonNull)
			.map(String.class::cast)
			.toList());
		userRepository.findProfileImageUrlById(userId)
			.map(this::toS3Key)
			.filter(Objects::nonNull)
			.ifPresent(keys::add);
		return keys;
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
				log.error("S3 객체 정리 실패 — 고아로 남는다: {}", response.errors());
			}
		} catch (SdkException e) {
			log.error("S3 객체 정리 호출 실패 — 고아로 남는다: keys={}", targets, e);
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
