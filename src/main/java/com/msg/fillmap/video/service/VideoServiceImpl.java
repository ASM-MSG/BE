package com.msg.fillmap.video.service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.locationtech.jts.geom.Point;
import org.springframework.core.task.TaskRejectedException;
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
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.KoreaCoordinates;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.video.dto.GridCoverVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoResponseDto;
import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.PresignedUrlResponseDto;
import com.msg.fillmap.video.dto.VideoPlaybackResponseDto;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoReplaceResponseDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.dto.VideoVisibilityRequestDto;
import com.msg.fillmap.video.dto.VideoVisibilityResponseDto;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

// ponytail: presign 을 VideoServiceImpl 에 합침. MSG-71 에서 S3 관심사가 2개째면 PresignedUrlService 로 분리.
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

	// 허용 확장자 → 정규 Content-Type — MSG-64 D2. 쌍으로 검증해 엇갈린 조합을 막는다.
	private static final Map<String, String> ALLOWED_TYPES = Map.of(
		"mp4", "video/mp4",
		"mov", "video/quicktime");

	private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);

	// 발급은 pending 으로, 확정되면 original 로 옮긴다 — pending 만 라이프사이클로 만료시키기 위해서다(MSG-133).
	// 한 prefix 를 쓰면 고아와 정상 영상이 섞여 만료 규칙을 걸 수 없다.
	private static final String PENDING_PREFIX = "videos/pending/";
	private static final String ORIGINAL_PREFIX = "videos/original/";

	private final VideoRepository videoRepository;
	private final VideoEncodingService videoEncodingService;
	private final VideoStatusWriter videoStatusWriter;
	private final S3Presigner s3Presigner;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;
	private final RegionStatsCommandService regionStatsCommandService;
	private final ThumbnailUrlPresigner thumbnailUrlPresigner;

	@Override
	@Transactional
	public VideoUploadResponseDto saveVideo(long userId, VideoUploadRequestDto request) {
		double lat = request.lat();
		double lon = request.lon();
		validateCoordinate(lat, lon);
		String originalKey = confirmUpload(userId, request.s3Key());

		String gridId = GridEncoder.encode(lat, lon);
		registerGridIfAbsent(gridId);

		boolean alreadyOccupied = videoRepository.existsUserGrid(userId, gridId);

		Point geom = GeoSupport.toPoint(lat, lon);
		Video video = videoRepository.save(
			Video.create(userId, gridId, originalKey, geom, request.durationSec(), request.recordedAt()));

		copyToOriginal(request.s3Key(), originalKey);
		videoRepository.upsertUserGrid(userId, gridId, video.getId());
		if (!alreadyOccupied) {
			// 첫 점령 — 그 격자 중심 행정동의 수집률 캐시를 같은 트랜잭션에서 갱신한다 (MSG-155).
			regionStatsCommandService.refresh(userId, gridId);
		}
		triggerEncodingAfterCommit(video.getId());

		return new VideoUploadResponseDto(
			video.getId(), gridId, video.getProcessingStatus().name(), !alreadyOccupied);
	}

	@Override
	@Transactional
	public VideoReplaceResponseDto replaceVideo(long userId, long videoId, VideoReplaceRequestDto request) {
		Video video = findOwnedVideo(userId, videoId);
		if (video.isDeleted()) {
			throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);   // 지운 영상은 되살리지 않는다
		}
		// 교체도 s3Key 를 받으므로 업로드와 같은 검증이 필요하다 — 없으면 "교체로 가짜 키 밀어넣기"가
		// 되어 MSG-132 에서 막은 구멍이 옆문으로 다시 열린다. (스펙 D4 에는 없던 보강)
		String originalKey = confirmUpload(userId, request.s3Key());
		validateSameGrid(video, request);

		// replaceFile 이 필드를 덮어쓰므로 그 전에 잡아둔다.
		String replacedKey = video.getOriginalS3Key();
		String replacedBlurredKey = video.getBlurredS3Key();

		video.replaceFile(originalKey, request.durationSec(), request.recordedAt());
		copyToOriginal(request.s3Key(), originalKey);

		// 교체된 원본은 참조를 잃는다. 인코딩본·썸네일은 키가 videoId 기반이라 재인코딩이 같은 자리에
		// 덮어쓰므로 지울 게 없다 — 고아가 되는 건 옛 original 과 블러본(MSG-145)이다.
		afterCommit(() -> deleteQuietly(replacedKey, replacedBlurredKey));
		triggerEncodingAfterCommit(videoId);
		return VideoReplaceResponseDto.from(video);
	}

	@Override
	@Transactional
	public VideoVisibilityResponseDto setVisibility(long userId, long videoId, VideoVisibilityRequestDto request) {
		Video video = findOwnedVideo(userId, videoId);
		if (video.isDeleted()) {
			throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);   // 지운 영상은 공개로 되살리지 않는다
		}
		video.changeVisibility(parseVisibility(request.visibility()));
		return VideoVisibilityResponseDto.from(video);
	}

	/**
	 * 클라이언트 문자열 → Visibility. AuthController.parseProvider 선례처럼 valueOf 실패를 잡아 4xx 로 준다 —
	 * request 를 enum 으로 받았다면 역직렬화 실패가 500 이 됐을 자리다 (MSG-162).
	 */
	private Visibility parseVisibility(String visibility) {
		try {
			// Locale.ROOT: 터키어 로케일 JVM에서 "private"→"PRİVATE"가 되는 배포 로케일 의존 차단
			return Visibility.valueOf(visibility.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(VideoErrorCode.INVALID_VISIBILITY);
		}
	}

	/**
	 * 좌표는 선택이다 — 안 보내면 파일만 교체하고 격자는 그대로 둔다.
	 * 보냈다면 같은 격자여야 한다 (MSG-71 D3): 격자가 바뀌면 옛 격자의 점령 롤백 + 새 격자 점령이
	 * 얽혀 복잡도가 급증하는데, MVP 에 그만한 값어치가 없다.
	 */
	private void validateSameGrid(Video video, VideoReplaceRequestDto request) {
		if (request.hasPartialCoordinate()) {
			throw new ApiException(VideoErrorCode.INVALID_COORDINATE);   // lat/lon 은 쌍으로만
		}
		if (!request.hasCoordinate()) {
			return;
		}
		validateCoordinate(request.lat(), request.lon());
		if (!GridEncoder.encode(request.lat(), request.lon()).equals(video.getGridId())) {
			throw new ApiException(VideoErrorCode.GRID_MISMATCH);
		}
	}

	/** 소유권 검증 — 교체·삭제가 공유한다 (MSG-71 스펙: "소유권 검증(MSG-72와 공통 헬퍼)"). */
	private Video findOwnedVideo(long userId, long videoId) {
		Video video = videoRepository.findById(videoId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		if (video.getUserId() != userId) {
			throw new ApiException(VideoErrorCode.VIDEO_FORBIDDEN);
		}
		return video;
	}

	@Override
	@Transactional
	public void deleteVideo(long userId, long videoId) {
		Video video = findOwnedVideo(userId, videoId);
		if (video.isDeleted()) {
			return;   // 중복 삭제는 멱등하게 성공 (MSG-72 D7)
		}

		// 아래 native 쿼리들은 이 변경을 본다 — Hibernate 가 native 쿼리 전에 영속성 컨텍스트를
		// 자동 flush 하기 때문이다(FlushMode.AUTO). 그러지 않으면 cover 재선정이 방금 지운 영상을
		// 다시 고른다. VideoDeleteIntegrationTest 의 cover 재선정 테스트가 이 순서를 지킨다.
		video.markDeleted();

		// 지웠으면 실제로 지운다 (MSG-133). MSG-72 D2 의 "즉시 삭제 안 함"은 보존 원칙이 아니라
		// "정리는 별도 배치 백로그"라는 범위 유예였고, undelete 기능은 없다. 파일이 영원히 남는 쪽이
		// 오히려 문제다. 시점에 지우면 배치·스케줄러가 통째로 필요 없다.
		afterCommit(() -> deleteQuietly(
			video.getOriginalS3Key(), video.getEncodedUrl(), video.getThumbnailUrl(),
			video.getBlurredS3Key()));

		String gridId = video.getGridId();
		videoRepository.decrementVideoCount(userId, gridId);
		if (videoRepository.deleteUserGridIfEmpty(userId, gridId) == 0) {
			// 아직 그 격자에 내 영상이 남아 있다 — 점령은 유지하고 cover 만 정리한다.
			videoRepository.reselectCover(userId, gridId, videoId);
		} else {
			// 점령 롤백 — 그 격자 중심 행정동의 수집률 캐시를 같은 트랜잭션에서 갱신한다 (MSG-155).
			regionStatsCommandService.refresh(userId, gridId);
		}
	}

	/**
	 * S3 객체 삭제. null 키는 건너뛴다 — 인코딩 전 영상은 encoded/thumb 가 아직 없다.
	 *
	 * 커밋 이후에 도는 정리 작업이라 실패해도 되돌릴 수 없다. 그대로 두면 이미 커밋된 삭제 요청이
	 * 500 으로 응답돼 사용자는 "삭제 실패"로 알지만 실제로는 지워진 상태가 된다. 남은 객체는 비용·위생
	 * 문제일 뿐이므로 로그만 남기고 삼킨다.
	 *
	 * DeleteObjects 는 배치 API 라 개별 객체 실패를 예외로 던지지 않는다 — 권한이 없어도 HTTP 200 에
	 * errors 를 담아 돌려준다. 응답을 안 보면 삭제가 조용히 실패한다(IAM 에 s3:DeleteObject 가 빠진 채
	 * 배포되면 정확히 그렇게 된다).
	 */
	private void deleteQuietly(String... s3Keys) {
		List<ObjectIdentifier> targets = Arrays.stream(s3Keys)
			.filter(Objects::nonNull)
			.map(key -> ObjectIdentifier.builder().key(key).build())
			.toList();
		if (targets.isEmpty()) {
			return;
		}
		try {
			DeleteObjectsResponse response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.delete(Delete.builder().objects(targets).build())
				.build());
			if (response.hasErrors()) {
				log.error("S3 객체 삭제 실패 — 고아로 남는다: {}", response.errors());
			}
		} catch (SdkException e) {
			log.error("S3 객체 삭제 호출 실패 — 고아로 남는다: keys={}", targets, e);
		}
	}

	/**
	 * 인코딩은 커밋 이후에 띄운다. @Async 는 별도 스레드라 여기서 바로 호출하면 아직 커밋되지 않은
	 * videos row 를 조회해 "영상 없음"으로 실패할 수 있다 (MSG-65 트리거 타이밍).
	 */
	private void triggerEncodingAfterCommit(Long videoId) {
		afterCommit(() -> submitEncoding(videoId));
	}

	/** 트랜잭션이 없으면(테스트 등) 그냥 지금 실행한다. */
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

	/**
	 * 큐가 가득 차면 executor 가 TaskRejectedException 을 던지는데, 이는 @Async 메서드 본문이 아니라
	 * submit 을 호출한 이 스레드에서 터진다. 그냥 두면 afterCommit 밖으로 전파돼 이미 커밋된 업로드가
	 * 500 으로 응답되고(클라이언트는 재시도 → 중복 업로드), 인코딩 쪽 catch 는 실행조차 되지 않아
	 * 영상이 UPLOADED 로 남는다. 그래서 여기서 삼키고 FAILED 로 기록한다.
	 */
	private void submitEncoding(Long videoId) {
		try {
			videoEncodingService.encode(videoId);
		} catch (TaskRejectedException e) {
			log.error("인코딩 큐 포화로 작업이 거부됨: videoId={}", videoId, e);
			videoStatusWriter.markFailed(videoId);
		}
	}

	@Override
	public PresignedUrlResponseDto issuePresignedUrl(long userId, PresignedUrlRequestDto request) {
		String extension = request.extension().toLowerCase();
		String allowedType = ALLOWED_TYPES.get(extension);
		if (allowedType == null || !allowedType.equals(request.contentType())) {
			throw new ApiException(VideoErrorCode.UNSUPPORTED_EXTENSION);
		}
		if (request.contentLength() > awsProperties.s3().maxUploadBytes()) {
			throw new ApiException(VideoErrorCode.FILE_TOO_LARGE);
		}

		String s3Key = "%s%d/%s.%s".formatted(PENDING_PREFIX, userId, UUID.randomUUID(), extension);

		// contentLength/contentType 을 서명에 포함시켜 클라이언트가 선언과 다른 크기·타입으로 올리면 S3 가 403 을 낸다.
		// (PUT presign 에는 POST policy 의 content-length-range 같은 범위 조건이 없다 — MSG-64 D3)
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

		return new PresignedUrlResponseDto(uploadUrl, s3Key, PRESIGN_TTL.toSeconds());
	}

	@Override
	@Transactional(readOnly = true)
	public List<GridVideoResponseDto> getGridVideos(long userId, String gridId) {
		return videoRepository
			.findByUserIdAndGridIdAndStatusOrderByCreatedAtDesc(userId, gridId, VideoStatus.ACTIVE)
			.stream()
			.map(video -> GridVideoResponseDto.of(video, thumbnailUrlPresigner.presign(video.getThumbnailUrl())))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public GridCoverVideoResponseDto getGridCover(String gridId) {
		return videoRepository.findGlobalCover(gridId)
			.map(video -> GridCoverVideoResponseDto.of(video, thumbnailUrlPresigner.presign(video.getThumbnailUrl())))
			.orElse(null);
	}

	/**
	 * 단건 영상 재생 조회 (MSG-206). @Transactional 은 readOnly 가 아니다 — 조회수 증가 UPDATE 때문이다.
	 * 접근 제어는 §도메인 2 순서(존재/DELETED → BLINDED → visibility → processing_status)로 first-match 판정한다.
	 */
	@Override
	@Transactional
	public VideoPlaybackResponseDto getVideoPlayback(long userId, long videoId) {
		Video video = videoRepository.findById(videoId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		boolean owner = video.getUserId() == userId;

		// 1. 존재/DELETED — 지운 영상은 소유자 포함 전원에게 존재 자체를 숨긴다.
		if (video.isDeleted()) {
			throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);
		}
		// 2. BLINDED — 타인이면 존재 은닉(404), 소유자면 통과하되 재생 불가로 처리(status!=ACTIVE 라 아래서 발급 안 됨).
		if (video.getStatus() == VideoStatus.BLINDED) {
			if (!owner) {
				throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);
			}
		} else if (video.getVisibility() == Visibility.PRIVATE && !owner) {
			// 3. visibility — PRIVATE 는 존재는 노출하되 접근만 막는다(404 아니라 403, 티켓 확정).
			// 공유 상수의 기본 메시지("본인의 영상만 처리...")는 수정 거부 뉘앙스라 조회 맥락 문구로 override.
			throw new ApiException(VideoErrorCode.VIDEO_FORBIDDEN, "비공개 영상입니다");
		}

		// 4. 재생 소스 선택 & presign — ACTIVE·READY 만 발급. 블러본이 있으면 우선, 없으면 인코딩본.
		String playbackUrl = null;
		Long expiresInSec = null;
		if (video.getStatus() == VideoStatus.ACTIVE && video.getProcessingStatus() == ProcessingStatus.READY) {
			String playbackKey = video.getBlurredS3Key() != null ? video.getBlurredS3Key() : video.getEncodedUrl();
			playbackUrl = thumbnailUrlPresigner.presign(playbackKey);
			// key 가 null 인 기형 READY 행(정상 markReady 경로엔 불가)이면 presign 이 null 이라 TTL 도 비운다 —
			// playbackUrl=null 인데 expiresInSec 만 남는 걸 막는다(성공기준 8).
			if (playbackUrl != null) {
				expiresInSec = thumbnailUrlPresigner.ttlSeconds();
			}
		}
		String thumbnailUrl = thumbnailUrlPresigner.presign(video.getThumbnailUrl());

		// 5. 조회수 증가 — 재생 URL 을 실제로 발급했고 타인일 때만 원자적 +1(소유자 본인 조회는 제외).
		if (playbackUrl != null && !owner) {
			videoRepository.incrementViewCount(video.getId());
		}

		// viewCount 는 증가 전 스냅샷 — native UPDATE 는 로드된 엔티티 필드를 건드리지 않는다(§설계 M7).
		return VideoPlaybackResponseDto.of(video, playbackUrl, thumbnailUrl, expiresInSec);
	}

	private void validateCoordinate(double lat, double lon) {
		if (KoreaCoordinates.isOutOfService(lat, lon)) {
			throw new ApiException(VideoErrorCode.INVALID_COORDINATE);
		}
	}

	/**
	 * 확정 요청의 pending 키가 "내가 실제로 올린, 아직 안 쓴 파일"인지 확인하고(MSG-132)
	 * original 로 옮긴 뒤 그 키를 돌려준다(MSG-133).
	 *
	 * 검증이 없으면 파일을 올리지 않고 좌표만 찍어서 격자를 점령할 수 있다 — upsertUserGrid 가
	 * 인코딩보다 먼저 돌고, 인코딩이 FAILED 가 돼도 점령은 남기 때문이다. FillMap 은 직접 가서 찍어야
	 * 격자를 채우는 게임이라 이건 게임 자체를 무너뜨린다.
	 *
	 * prefix 검사만으론 부족하다 — 공격자는 자기 userId 를 알기에 videos/pending/{내id}/아무거나.mp4 를
	 * 지어낼 수 있다. S3 에 실제로 있는지(headObject) 봐야 막힌다.
	 */
	private String confirmUpload(long userId, String pendingKey) {
		if (!pendingKey.startsWith("%s%d/".formatted(PENDING_PREFIX, userId))) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		String originalKey = ORIGINAL_PREFIX + pendingKey.substring(PENDING_PREFIX.length());

		// 영상 1개로 좌표만 바꿔가며 무한 점령하는 걸 막는다. DB 의 UNIQUE 제약이 최종 방어선이고,
		// 여기서 미리 걸러 500 대신 4xx 를 준다.
		//
		// 반드시 originalKey 로 조회해야 한다 — DB 에는 original 키만 저장되므로 클라이언트가 준
		// pendingKey 로 조회하면 영영 매치되지 않아 중복 검사가 통째로 무력화된다.
		if (videoRepository.existsByOriginalS3Key(originalKey)) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		requireObjectExists(pendingKey);
		return originalKey;
	}

	/**
	 * pending → original 서버측 복사. pending 원본은 지우지 않는다 — 라이프사이클이 어차피 쓸어가므로
	 * (고아든 복사 완료본이든 똑같이) 삭제 호출 하나와 그 실패 경로를 없앤다. 대가는 하루치 중복 저장뿐.
	 *
	 * 호출부에서 videos INSERT 이후에 부르는 이유: 복사가 실패하면 트랜잭션이 롤백돼 row 가 안 남고
	 * pending 은 만료된다. 반대 순서면 original 쪽에 라이프사이클이 못 잡는 고아가 생긴다.
	 */
	private void copyToOriginal(String pendingKey, String originalKey) {
		s3Client.copyObject(CopyObjectRequest.builder()
			.sourceBucket(awsProperties.s3().bucket())
			.sourceKey(pendingKey)
			.destinationBucket(awsProperties.s3().bucket())
			.destinationKey(originalKey)
			.build());
	}

	private void requireObjectExists(String s3Key) {
		try {
			s3Client.headObject(HeadObjectRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.key(s3Key)
				.build());
		} catch (NoSuchKeyException e) {
			throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
		} catch (S3Exception e) {
			// HeadObject 는 본문 없는 404 를 주므로 SDK 가 NoSuchKeyException 으로 못 좁히는 경우가 있다.
			if (e.statusCode() == 404) {
				throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
			}
			throw e;
		}
	}

	private void registerGridIfAbsent(String gridId) {
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(
			gridId, index.gridY(), index.gridX(), center.lat(), center.lon(), GeoSupport.bboxWkt(gridId));
	}
}
