package com.msg.fillmap.video.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
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

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.friend.service.FriendService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.KoreaCoordinates;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.GridCoverVideoResponseDto;
import com.msg.fillmap.video.dto.GridGlobalVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
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
import com.msg.fillmap.video.support.VideoCursor;

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

	// 미래 recordedAt 허용 오차 (MSG-278 PRD §8) — 단말 시계 스큐 커버. 상수 고정, 설정화하지 않는다.
	private static final Duration RECORDED_AT_TOLERANCE = Duration.ofMinutes(5);

	// 발급은 pending 으로, 확정되면 original 로 옮긴다 — pending 만 라이프사이클로 만료시키기 위해서다(MSG-133).
	// 한 prefix 를 쓰면 고아와 정상 영상이 섞여 만료 규칙을 걸 수 없다.
	private static final String PENDING_PREFIX = "videos/pending/";
	private static final String ORIGINAL_PREFIX = "videos/original/";

	// 전역 목록 페이지 크기 (MSG-237 §D5). 범위 밖은 에러가 아니라 클램프한다 — MSG-156 LEAST clamp 선례.
	private static final int GLOBAL_PAGE_DEFAULT_SIZE = 20;
	private static final int GLOBAL_PAGE_MAX_SIZE = 50;

	private final VideoRepository videoRepository;
	private final VideoEncodingService videoEncodingService;
	private final VideoStatusWriter videoStatusWriter;
	private final S3Presigner s3Presigner;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;
	private final RegionStatsCommandService regionStatsCommandService;
	private final ThumbnailUrlPresigner thumbnailUrlPresigner;
	private final BadgeAwardService badgeAwardService;
	private final StreakCommandService streakCommandService;
	private final MissionAwardService missionAwardService;
	private final HotScoreCommandService hotScoreCommandService;
	// 재생 판정의 FRIENDS 분기만 쓰는 B-내부 의존 (MSG-285) — video → friend 단방향, 순환 없음.
	private final FriendService friendService;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — 마지막 인자 clock 을 Clock.systemUTC() 로 고정해 Lombok 전체 생성자로 위임한다.
	 * systemUTC 인 이유: recordedAt 은 UTC 순간으로 해석된다(findCompleted 가 UTC 저장 미션 기간과 직접
	 * 비교 — MSG-278 §D1). 기본존(KST JVM)이면 now 가 +9h 앞서 판정이 환경별로 갈린다(MSG-222 전례).
	 * 전체 생성자(@RequiredArgsConstructor 생성)는 테스트 고정 클럭 주입용이다.
	 */
	@Autowired
	public VideoServiceImpl(VideoRepository videoRepository, VideoEncodingService videoEncodingService,
		VideoStatusWriter videoStatusWriter, S3Presigner s3Presigner, S3Client s3Client, AwsProperties awsProperties,
		RegionStatsCommandService regionStatsCommandService, ThumbnailUrlPresigner thumbnailUrlPresigner,
		BadgeAwardService badgeAwardService, StreakCommandService streakCommandService,
		MissionAwardService missionAwardService, HotScoreCommandService hotScoreCommandService,
		FriendService friendService) {
		this(videoRepository, videoEncodingService, videoStatusWriter, s3Presigner, s3Client, awsProperties,
			regionStatsCommandService, thumbnailUrlPresigner, badgeAwardService, streakCommandService,
			missionAwardService, hotScoreCommandService, friendService, Clock.systemUTC());
	}

	@Override
	@Transactional
	public VideoUploadResponseDto saveVideo(long userId, VideoUploadRequestDto request) {
		// confirmUpload(S3 원본 키 클레임) 전에 확정한다 — 잘못된 값이 S3 부수효과 없이 거부되게 (MSG-204 FR-3).
		// 미지정(null)은 PUBLIC: "올리면 지도에 게시된다"는 제품 기본. 빈 문자열·오타는 parseVisibility 가 3420 으로 거른다.
		Visibility visibility = request.visibility() == null
			? Visibility.PUBLIC
			: parseVisibility(request.visibility());
		double lat = request.lat();
		double lon = request.lon();
		validateCoordinate(lat, lon);
		validateRecordedAt(request.recordedAt());
		String originalKey = confirmUpload(userId, request.s3Key());

		String gridId = GridEncoder.encode(lat, lon);
		registerGridIfAbsent(gridId);

		boolean alreadyOccupied = videoRepository.existsUserGrid(userId, gridId);

		Point geom = GeoSupport.toPoint(lat, lon);
		// saveAndFlush: original_s3_key 클레임(INSERT)을 S3 복사보다 먼저 확정한다 (MSG-247 1R 클레임 선행).
		// IDENTITY 라 save 도 즉시 INSERT 지만, ID 전략이 바뀌어도 순서가 유지되게 명시한다.
		Video video = videoRepository.saveAndFlush(
			Video.create(userId, gridId, originalKey, geom, request.durationSec(), request.recordedAt(), visibility));

		copyToOriginal(request.s3Key(), originalKey);
		videoRepository.upsertUserGrid(userId, gridId, video.getId());

		// 뱃지 지급 훅 (MSG-239): 업로드와 같은 트랜잭션에서 동기 판정하고, 새로 획득한 뱃지를 응답에 실어
		// FE 가 획득 연출을 하게 한다. 전 종류를 훑지 않고 "이 행동으로 딸 수 있는 종류"만 판정한다 —
		// 업로드는 항상 업로드 수 뱃지, 처음 수집한 격자면 총 격자 수·행정동 수집률 뱃지를 추가 판정.
		// metric 계산은 뱃지 도메인 몫이라 여기서는 행동 단위 호출만 한다. 상세 결정: docs/MSG-239.md §D3~D5.
		List<EarnedBadgeResponseDto> newBadges = new ArrayList<>(badgeAwardService.awardUploadBadges(userId));
		if (!alreadyOccupied) {
			// 첫 점령 — 그 격자 중심 행정동의 수집률 캐시를 같은 트랜잭션에서 갱신한다 (MSG-155).
			// 순서 중요: awardCollectionBadges 의 수집률 판정이 refresh 가 방금 저장한 값을 읽는다.
			regionStatsCommandService.refresh(userId, gridId);
			newBadges.addAll(badgeAwardService.awardCollectionBadges(userId, gridId));
		}
		// 스트릭 (MSG-200): 아무 업로드(재방문 포함)가 인정 이벤트라 분기 바깥. 갱신·꾸준함 뱃지 판정은
		// 스트릭 도메인 몫 — 여기서는 획득분을 응답에 합류시키기만 한다.
		newBadges.addAll(streakCommandService.recordUpload(userId));
		// 미션 판정 (MSG-223): 방문(videos 이벤트)이 근거라 이 역시 분기 바깥. 판정·스탬프·MISSION_COUNT
		// 뱃지는 미션 도메인 몫 — 여기서는 완료 스탬프를 응답에 싣고 획득 뱃지를 합류시키기만 한다(§D2).
		MissionAwardResult missionAward = missionAwardService.awardOnUpload(userId, gridId);
		newBadges.addAll(missionAward.newBadges());
		// 핫스코어 (MSG-233): 커밋 후 증분 — 롤백 시 유령 증분 방지. 실패는 구현이 삼킨다 (FR-6).
		afterCommit(() -> hotScoreCommandService.recordUpload(gridId));
		triggerEncodingAfterCommit(video.getId(), originalKey);

		return new VideoUploadResponseDto(
			video.getId(), gridId, video.getProcessingStatus().name(), !alreadyOccupied, newBadges,
			missionAward.completedMissions());
	}

	@Override
	@Transactional
	public VideoReplaceResponseDto replaceVideo(long userId, long videoId, VideoReplaceRequestDto request) {
		Video video = findOwnedVideo(userId, videoId);
		if (video.isDeleted()) {
			throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);   // 지운 영상은 되살리지 않는다
		}
		// 교체도 recordedAt 을 그대로 반영하므로 업로드와 같은 검증을 지난다 — 교체로 우회 불가 (MSG-278 FR-2).
		// 소유권(3403)·존재(3404)가 먼저인 게 기존 에러 우선순위와 일관, confirmUpload 전이라 S3 부수효과 없음.
		validateRecordedAt(request.recordedAt());
		// 교체도 s3Key 를 받으므로 업로드와 같은 검증이 필요하다 — 없으면 "교체로 가짜 키 밀어넣기"가
		// 되어 MSG-132 에서 막은 구멍이 옆문으로 다시 열린다. (스펙 D4 에는 없던 보강)
		String originalKey = confirmUpload(userId, request.s3Key());
		validateSameGrid(video, request);

		// replaceFile 이 필드를 덮어쓰므로 그 전에 잡아둔다.
		String replacedKey = video.getOriginalS3Key();
		String replacedBlurredKey = video.getBlurredS3Key();

		video.replaceFile(originalKey, request.durationSec(), request.recordedAt());
		// 클레임 선행 (MSG-247 1R): original_s3_key 클레임(UPDATE)을 S3 복사보다 먼저 flush 한다.
		// 동시 이중 확정 직렬화는 2R 부터 confirmUpload 의 advisory lock 몫이고, 이 flush 는
		// "DB 클레임 없이 복사된 객체가 없다"는 순서 보장으로 남는다(무해 — 2R 에서 유지 결정).
		videoRepository.flush();
		copyToOriginal(request.s3Key(), originalKey);

		// 교체된 원본은 참조를 잃는다. 인코딩본·썸네일은 키가 videoId 기반이라 재인코딩이 같은 자리에
		// 덮어쓰므로 지울 게 없다 — 고아가 되는 건 옛 original 과 블러본(MSG-145)이다.
		afterCommit(() -> deleteQuietly(replacedKey, replacedBlurredKey));
		triggerEncodingAfterCommit(videoId, originalKey);
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

	/** 소유권 검증 — 교체·공개설정이 공유한다. 삭제는 잠금 로드로 같은 검사를 한다 (MSG-243). */
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
		// 잠금 로드 (MSG-243). findOwnedVideo 의 일반 로드는 동시 삭제 2건이 모두 아래 멱등 가드를 통과해
		// video_count 가 이중 감소한다 — ACTIVE 영상이 남았는데 점령이 오롤백되는 데이터 유실. 행 잠금
		// (MSG-149 findWithLockById 재사용)으로 전이를 직렬화하면 패자는 대기 후 재조회에서 DELETED 를 보고
		// 가드에서 반환하므로 감소·S3 삭제 등록·수집률 refresh 가 승자 1회만 실행된다. 잠금 유지 구간은
		// DB 쓰기뿐이다 — S3 작업은 전부 afterCommit 이라 잠금이 길어지지 않는다.
		Video video = videoRepository.findWithLockById(videoId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		if (video.getUserId() != userId) {
			throw new ApiException(VideoErrorCode.VIDEO_FORBIDDEN);
		}
		if (video.isDeleted()) {
			return;   // 중복 삭제는 멱등하게 성공 (MSG-72 D7) — 동시 삭제의 패자도 여기로 (MSG-243)
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
	private void triggerEncodingAfterCommit(Long videoId, String originalKey) {
		afterCommit(() -> submitEncoding(videoId, originalKey));
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
	private void submitEncoding(Long videoId, String originalKey) {
		try {
			videoEncodingService.encode(videoId, originalKey);
		} catch (TaskRejectedException e) {
			log.error("인코딩 큐 포화로 작업이 거부됨: videoId={}", videoId, e);
			videoStatusWriter.markFailed(videoId, originalKey);
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
	 * 격자 전역 영상 목록 조회 (MSG-237). userId 없음 — 전역 선정이라 결과가 호출자와 무관하다(§D1·D4).
	 * 필터·정렬은 repository(idx_videos_grid_popular 일치)가 정본이고, 여기서는 size 클램프 →
	 * lookahead(size+1) 조회 → hasNext 판정·트림 → 항목 presign → nextCursor 발급만 한다 (MSG-90 패턴).
	 */
	@Override
	@Transactional(readOnly = true)
	public GridVideoPageResponseDto getGridGlobalVideos(String gridId, String cursor, int size) {
		int pageSize = size < 1 ? GLOBAL_PAGE_DEFAULT_SIZE : Math.min(size, GLOBAL_PAGE_MAX_SIZE);
		List<Video> rows = queryGlobalPage(gridId, cursor, pageSize + 1);
		boolean hasNext = rows.size() > pageSize;
		List<Video> pageRows = hasNext ? rows.subList(0, pageSize) : rows;
		List<GridGlobalVideoResponseDto> videos = pageRows.stream()
			.map(video -> GridGlobalVideoResponseDto.of(video, thumbnailUrlPresigner.presign(video.getThumbnailUrl())))
			.toList();
		String nextCursor = null;
		if (hasNext) {
			Video last = pageRows.get(pageRows.size() - 1);
			nextCursor = VideoCursor.encode(gridId, last.getViewCount(), last.getCreatedAt(), last.getId());
		}
		return new GridVideoPageResponseDto(videos, hasNext, nextCursor);
	}

	private List<Video> queryGlobalPage(String gridId, String cursor, int limit) {
		if (cursor == null) {
			return videoRepository.findGlobalVideos(gridId, limit);
		}
		VideoCursor decoded = decodeGlobalCursor(cursor);
		if (!decoded.gridId().equals(gridId)) {
			// 다른 격자에서 발급된 커서 — 경계값이 이 격자의 keyset 으로 오적용돼 결과가 조용히 잘리는 걸
			// 막는다 (2026-07-28 Codex 교차 리뷰 P2). 형식 위반과 같은 무효 커서로 취급한다.
			throw new ApiException(VideoErrorCode.INVALID_CURSOR);
		}
		return videoRepository.findGlobalVideosAfter(
			gridId, decoded.viewCount(), decoded.createdAt(), decoded.id(), limit);
	}

	/** 무효 커서는 조용히 첫 페이지로 폴백하지 않고 400 으로 거른다 — FE 버그가 무한 첫 페이지 루프로 은폐되는 걸 막는다(§D5). */
	private VideoCursor decodeGlobalCursor(String cursor) {
		try {
			return VideoCursor.decode(cursor);
		} catch (RuntimeException e) {
			throw new ApiException(VideoErrorCode.INVALID_CURSOR, e);
		}
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
		} else if (!owner) {
			// 3. visibility — 통과할 값을 명시한다(MSG-285 §D3). "PRIVATE 만 차단"이라는 부정형이면 새 공개범위가
			// 조용히 전원 공개로 새기 때문이다 — FRIENDS 추가가 바로 그 회귀 지점이었다.
			// 문(statement)이 아니라 식(expression)인 이유: javac 은 식에서만 미포함 상수를 컴파일 에러로 잡는다
			// (문은 그냥 통과). 4값째가 생기면 여기서 컴파일이 깨져 판정 재검토가 강제된다 — §D3 의 목적.
			boolean visible = switch (video.getVisibility()) {
				case PUBLIC -> true;
				// 친구 조회는 이 분기에서만 1회 — PUBLIC·PRIVATE·소유자 경로는 쿼리 0회다.
				// 캐시 없는 요청 시점 판정이라 친구 삭제가 다음 요청부터 즉시 반영된다 (FR-6).
				case FRIENDS -> friendService.isFriend(video.getUserId(), userId);
				case PRIVATE -> false;
			};
			if (!visible) {
				// PRIVATE 는 존재는 노출하되 접근만 막는다(404 아니라 403, 티켓 확정). 비친구의 FRIENDS 도
				// 같은 자리에서 던져 응답이 PRIVATE 와 바이트 단위로 같다 — 신규 에러코드 없음(§D1).
				// 공유 상수의 기본 메시지("본인의 영상만 처리...")는 수정 거부 뉘앙스라 조회 맥락 문구로 override.
				throw new ApiException(VideoErrorCode.VIDEO_FORBIDDEN, "비공개 영상입니다");
			}
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
	 * 클라 신고값 recordedAt 의 미래 시각 검증 (MSG-278) — 업로드·교체가 공유한다.
	 * findCompleted 가 이 값을 미션 기간과 직접 비교하므로, 미래 시각 위조로 끝난 축제의 스탬프를 따는
	 * 트리비얼 공격을 입력 경계에서 막는다. 경계 포함(isAfter — 초과만 거부), 모든 과거는 통과(갤러리 방문).
	 * "기간 안 과거 시각" 위조는 서명 없는 신고값이라 이 검증으로 못 막는다 — PRD 비목표, 잔존 위험 수용.
	 */
	private void validateRecordedAt(LocalDateTime recordedAt) {
		if (recordedAt.isAfter(LocalDateTime.now(clock).plus(RECORDED_AT_TOLERANCE))) {
			throw new ApiException(VideoErrorCode.RECORDED_AT_IN_FUTURE);
		}
	}

	/**
	 * 확정 요청의 pending 키가 "내가 실제로 올린, 아직 안 쓴 파일"인지 확인하고(MSG-132)
	 * 이번 시도의 original 키를 발급해 돌려준다(MSG-133 · MSG-247 2R).
	 *
	 * 검증이 없으면 파일을 올리지 않고 좌표만 찍어서 격자를 점령할 수 있다 — upsertUserGrid 가
	 * 인코딩보다 먼저 돌고, 인코딩이 FAILED 가 돼도 점령은 남기 때문이다. FillMap 은 직접 가서 찍어야
	 * 격자를 채우는 게임이라 이건 게임 자체를 무너뜨린다.
	 *
	 * prefix 검사만으론 부족하다 — 공격자는 자기 userId 를 알기에 videos/pending/{내id}/아무거나.mp4 를
	 * 지어낼 수 있다. S3 에 실제로 있는지(headObject) 봐야 막힌다.
	 *
	 * original 키는 pending 결정 파생이 아니라 확정 시도마다 새 UUID 다(Codex 2R P1): 목적지가 결정적이면
	 * 롤백된 시도의 보상 삭제와 후속 시도의 복사·커밋이 같은 키를 두고 순서 경쟁한다(각각 S3 호출 1회,
	 * 순서 미보장). 시도별 키는 목적지 공유 자체를 없애 그 레이스 클래스를 근절한다. 대신 이중 확정
	 * 차단은 UNIQUE 직렬화가 아니라 pending 키 advisory lock + pendingStem prefix 존재 검사로 유지한다 —
	 * 락이 같은 pending 의 확정을 직렬화하므로 락 획득 후의 검사는 앞 확정의 커밋을 반드시 본다.
	 * (구형 결정 파생 키로 이미 확정된 pending 은 정확 매치가 마저 걸러낸다 — 배포 전 데이터 호환.)
	 */
	private String confirmUpload(long userId, String pendingKey) {
		if (!pendingKey.startsWith("%s%d/".formatted(PENDING_PREFIX, userId))) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		String stem = pendingKey.substring(PENDING_PREFIX.length());   // "{userId}/{uuid}.{ext}"
		int extAt = stem.lastIndexOf('.');
		if (extAt < 0) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);   // presign 발급 키는 항상 확장자를 가진다
		}
		String claimPrefix = ORIGINAL_PREFIX + stem.substring(0, extAt) + "-";

		videoRepository.acquirePendingKeyConfirmLock(pendingKey);
		// 영상 1개로 좌표만 바꿔가며 무한 점령하는 걸 막는다(이중 확정 차단). prefix 매치가 시도별 키를,
		// 정확 매치가 구형 결정 키를 잡는다. StartingWith 의 LIKE 와일드카드는 매치를 넓힐 뿐이라
		// 거부 방향으로만 오작동 가능 — 이중 확정 우회로는 악용될 수 없다.
		if (videoRepository.existsByOriginalS3KeyStartingWith(claimPrefix)
			|| videoRepository.existsByOriginalS3Key(ORIGINAL_PREFIX + stem)) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		requireObjectExists(pendingKey);
		return claimPrefix + UUID.randomUUID() + stem.substring(extAt);
	}

	/**
	 * pending → original 서버측 복사. pending 원본은 지우지 않는다 — 라이프사이클이 어차피 쓸어가므로
	 * (고아든 복사 완료본이든 똑같이) 삭제 호출 하나와 그 실패 경로를 없앤다. 대가는 하루치 중복 저장뿐.
	 *
	 * 호출부에서 videos INSERT 이후에 부르는 이유: 복사가 실패하면 트랜잭션이 롤백돼 row 가 안 남고
	 * pending 은 만료된다. 반대 순서면 original 쪽에 라이프사이클이 못 잡는 고아가 생긴다.
	 *
	 * 복사 성공 이후 트랜잭션이 실패하면 DB 는 롤백돼도 original 복사본은 남는데, 라이프사이클이
	 * pending 전용이라 영구 고아가 된다 — 복사 직후 롤백 보상을 걸어 함께 정리한다(MSG-247).
	 * 복사 자체가 실패하면 대상 객체가 없으니 보상 불요(예외 전파 → 롤백 → pending 만료).
	 */
	private void copyToOriginal(String pendingKey, String originalKey) {
		s3Client.copyObject(CopyObjectRequest.builder()
			.sourceBucket(awsProperties.s3().bucket())
			.sourceKey(pendingKey)
			.destinationBucket(awsProperties.s3().bucket())
			.destinationKey(originalKey)
			.build());
		deleteOnRollback(originalKey);
	}

	/**
	 * afterCommit 의 롤백 대칭 (MSG-247) — 확정 트랜잭션이 롤백되면 방금 복사한 original 을 지운다.
	 * 트랜잭션 밖이면 롤백 개념이 없으니 아무것도 안 한다 — afterCommit 헬퍼의 "즉시 실행" 폴백과 반대다
	 * (즉시 실행하면 방금 복사한 원본을 그 자리에서 지워버린다).
	 *
	 * STATUS_ROLLED_BACK 에만 지운다 — STATUS_UNKNOWN(커밋 결과 불명)은 커밋됐을 수 있는 영상의 원본이라
	 * 지우면 데이터 유실(재생 불가)이고, 고아는 비용 문제일 뿐이다. 불명확하면 남기는 쪽이 안전.
	 * 삭제는 deleteQuietly 베스트 에포트 — 롤백 응답을 보상 실패로 다시 오염시키지 않는다.
	 *
	 * 불변식(시도별 유니크 키 — Codex 2R P1): original 키는 확정 시도마다 새 UUID 로 발급되므로
	 * 목적지 공유 자체가 불가능하다. 이 보상이 지우는 객체는 언제나 자기 시도만 참조하던 것이고,
	 * 후속 재시도의 복사·커밋과 이 삭제의 S3 호출 순서가 어긋나도 서로 다른 키라 무해하다.
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
