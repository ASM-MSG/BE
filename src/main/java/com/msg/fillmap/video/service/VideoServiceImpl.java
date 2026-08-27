package com.msg.fillmap.video.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.locationtech.jts.geom.Point;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.friend.service.FriendshipQueryService;
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
import com.msg.fillmap.video.dto.FriendGridVideoResponseDto;
import com.msg.fillmap.video.dto.GridCoverVideoResponseDto;
import com.msg.fillmap.video.dto.GridGlobalVideoResponseDto;
import com.msg.fillmap.video.dto.GridHourlyUploadResponseDto;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.dto.GridVideoResponseDto;
import com.msg.fillmap.video.dto.HourlyUploadCountResponseDto;
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
import com.msg.fillmap.video.repository.AuthorNicknameProjection;
import com.msg.fillmap.video.repository.HourlyUploadProjection;
import com.msg.fillmap.video.repository.VideoEncodingJobRepository;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.MissionVideoCursor;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.video.support.VideoCursor;
import com.msg.fillmap.video.support.VideoSignature;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

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

	// 확정 시점 컨테이너 판별용 범위 요청 크기 (MSG-392) — 판별에 필요한 건 박스 헤더 몇 개라 수십 바이트면
	// 충분하지만, 비용은 바이트 수가 아니라 왕복 1회가 지배하므로 여유 있게 잡는다.
	private static final int SIGNATURE_HEAD_BYTES = 4096;

	// 박스 헤더 하나(크기 4 + 타입 4)도 못 담는 크기 — 이 미만은 본문을 읽지 않고 거부한다.
	private static final int SIGNATURE_MIN_BYTES = 8;

	// 본문 4KB 호출은 전송량이 아니라 왕복만 드는 호출이라 다운로드 시한과 다른 급으로 잡는다
	// (HighlightPreviewServiceImpl.HEAD_TIMEOUT 과 같은 값·같은 이유). 설정 키로 빼지 않는다.
	private static final Duration SIGNATURE_READ_TIMEOUT = Duration.ofSeconds(10);

	// 발급은 pending 으로, 확정되면 original 로 옮긴다 — pending 만 라이프사이클로 만료시키기 위해서다(MSG-133).
	// 한 prefix 를 쓰면 고아와 정상 영상이 섞여 만료 규칙을 걸 수 없다.
	private static final String PENDING_PREFIX = "videos/pending/";
	private static final String ORIGINAL_PREFIX = "videos/original/";

	// 시간대 차트 구간 수 (MSG-372) — 하루 24시간 고정, 빈 구간도 응답에 실린다.
	private static final int HOURS_PER_DAY = 24;

	// 전역 목록 페이지 크기 (MSG-237 §D5). 범위 밖은 에러가 아니라 클램프한다 — MSG-156 LEAST clamp 선례.
	private static final int GLOBAL_PAGE_DEFAULT_SIZE = 20;
	private static final int GLOBAL_PAGE_MAX_SIZE = 50;

	private final VideoRepository videoRepository;
	private final VideoEncodingJobRepository videoEncodingJobRepository;
	private final S3Presigner s3Presigner;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;
	private final RegionStatsCommandService regionStatsCommandService;
	private final ThumbnailUrlPresigner thumbnailUrlPresigner;
	private final BadgeAwardService badgeAwardService;
	private final StreakCommandService streakCommandService;
	private final MissionAwardService missionAwardService;
	private final HotScoreCommandService hotScoreCommandService;
	// 재생 판정의 FRIENDS 분기만 쓰는 B-내부 의존 (MSG-285). friendships 만 읽는 leaf 라 friend 서비스를
	// 거치지 않는다 — MSG-187 D5 의 friend → video 위임과 맞물려도 순환이 생기지 않는다 (MSG-312).
	private final FriendshipQueryService friendshipQueryService;
	// 업로드 확정·재생 응답의 격자 표시명 (MSG-341). 단건 경로라 리졸버를 응답 조립 직전에 1회 받는다.
	private final ZoneNameQueryService zoneNameQueryService;
	// 행사 영상 판정 (MSG-440) — 공개범위 전환 차단 하나에만 쓴다. 같은 Owner B 내부 의존이고, 엔티티
	// 방향(event 가 video 참조)과 빈 방향(video 서비스가 event 리포지토리 참조)이 달라 순환이 없다.
	private final EventVideoRepository eventVideoRepository;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — 마지막 인자 clock 을 Clock.systemUTC() 로 고정해 Lombok 전체 생성자로 위임한다.
	 * systemUTC 인 이유: recordedAt 은 UTC 순간으로 해석된다(findCompleted 가 UTC 저장 미션 기간과 직접
	 * 비교 — MSG-278 §D1). 기본존(KST JVM)이면 now 가 +9h 앞서 판정이 환경별로 갈린다(MSG-222 전례).
	 * 전체 생성자(@RequiredArgsConstructor 생성)는 테스트 고정 클럭 주입용이다.
	 */
	@Autowired
	public VideoServiceImpl(VideoRepository videoRepository, VideoEncodingJobRepository videoEncodingJobRepository,
		S3Presigner s3Presigner, S3Client s3Client, AwsProperties awsProperties,
		RegionStatsCommandService regionStatsCommandService, ThumbnailUrlPresigner thumbnailUrlPresigner,
		BadgeAwardService badgeAwardService, StreakCommandService streakCommandService,
		MissionAwardService missionAwardService, HotScoreCommandService hotScoreCommandService,
		FriendshipQueryService friendshipQueryService, ZoneNameQueryService zoneNameQueryService,
		EventVideoRepository eventVideoRepository) {
		this(videoRepository, videoEncodingJobRepository, s3Presigner, s3Client, awsProperties,
			regionStatsCommandService, thumbnailUrlPresigner, badgeAwardService, streakCommandService,
			missionAwardService, hotScoreCommandService, friendshipQueryService, zoneNameQueryService,
			eventVideoRepository, Clock.systemUTC());
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
		double lon = request.lng();
		validateCoordinate(lat, lon);

		String gridId = GridEncoder.encode(lat, lon);
		ConfirmedVideo confirmed = confirmAndStore(userId, gridId, GeoSupport.toPoint(lat, lon), request.s3Key(),
			request.durationSec(), request.recordedAt(), visibility);
		Video video = confirmed.video();

		// 미션 판정 (MSG-223): 방문(videos 이벤트)이 근거라 점령 여부와 무관하게 매 업로드 1회. 판정·스탬프·
		// 종류별 미션 뱃지는 미션 도메인 몫 — 여기서는 완료 스탬프를 응답에 싣고 획득 뱃지를 합류시키기만
		// 한다(§D2). 코어 밖에 있는 이유는 행사 업로드가 이 훅만 타지 않기 때문이다 (MSG-440 제외 계약).
		MissionAwardResult missionAward = missionAwardService.awardOnUpload(userId, gridId);
		List<EarnedBadgeResponseDto> newBadges = new ArrayList<>(confirmed.newBadges());
		newBadges.addAll(missionAward.newBadges());

		// 격자 표시명 (MSG-341). 구역 이름은 순수 산술이고, 행정동 이름은 upsertGrid 가 방금 저장한 라벨을
		// 읽는다 — 좌표 재판정이 아니라 저장 라벨이라야 도감·카드 리스트의 regionName 과 같은 동이 나온다(D-6).
		ZoneCellName zoneCellName = zoneName(gridId);
		return new VideoUploadResponseDto(
			video.getId(), gridId, video.getProcessingStatus().name(), confirmed.occupied(), newBadges,
			missionAward.completedMissions(),
			zoneCellName.zoneName(), zoneCellName.zoneCell(), findRegionName(gridId));
	}

	/**
	 * 격자 지정 업로드 확정 (MSG-440) — 좌표 대신 호출자가 정한 격자에 확정한다. geom 은 그 격자의 셀
	 * 중심점이고 공개범위는 PUBLIC 고정이다(행사 피드는 공개 전제, MSG-438 확정).
	 * 미션 판정만 타지 않고 나머지 부수효과는 saveVideo 와 같은 코어 하나를 지난다.
	 */
	@Override
	@Transactional
	public ConfirmedVideo confirmAtGrid(long userId, String gridId, String s3Key, Short durationSec,
		LocalDateTime recordedAt) {
		GridPoint center = GridEncoder.center(gridId);
		return confirmAndStore(userId, gridId, GeoSupport.toPoint(center.lat(), center.lon()), s3Key,
			durationSec, recordedAt, Visibility.PUBLIC);
	}

	@Override
	@Transactional
	public Optional<Video> findConfirmedByPendingKey(String pendingKey) {
		// 확정과 같은 advisory lock 을 먼저 잡는다 — 락 획득자는 앞 확정의 커밋/롤백 이후에만 진입하므로
		// 이 조회는 "앞 시도가 커밋됐다면 반드시 본다". 락 없이 보면 재시도가 확정 중인 행을 못 보고
		// 새 확정으로 진입해 중복 영상이 된다. xact lock 이라 뒤따르는 confirmUpload 의 재획득은 무해하다.
		videoRepository.acquirePendingKeyConfirmLock(pendingKey);
		return claimPrefix(pendingKey).flatMap(videoRepository::findFirstByOriginalS3KeyStartingWith);
	}

	/**
	 * 업로드 확정 코어 (MSG-440 에서 추출) — 좌표 경로(saveVideo)와 격자 지정 경로(confirmAtGrid)가 공유한다.
	 * 순서가 계약이다: recordedAt 검증 → confirmUpload(pending 키 소유·이중 확정 차단·실측 크기) →
	 * grids lazy insert → 점령 여부 스냅숏 → videos INSERT → S3 복사 → 점령 UPSERT → 뱃지·스트릭 →
	 * 인코딩 작업 등록 → 커밋 후 핫스코어. 미션 판정은 코어 밖이다 — 행사 업로드가 그 훅 하나만 제외하기 때문이다.
	 */
	private ConfirmedVideo confirmAndStore(long userId, String gridId, Point geom, String s3Key, Short durationSec,
		LocalDateTime recordedAt, Visibility visibility) {
		validateRecordedAt(recordedAt);
		String originalKey = confirmUpload(userId, s3Key);

		registerGridIfAbsent(gridId);

		boolean alreadyOccupied = videoRepository.existsUserGrid(userId, gridId);

		// saveAndFlush: original_s3_key 클레임(INSERT)을 S3 복사보다 먼저 확정한다 (MSG-247 1R 클레임 선행).
		// IDENTITY 라 save 도 즉시 INSERT 지만, ID 전략이 바뀌어도 순서가 유지되게 명시한다.
		Video video = videoRepository.saveAndFlush(
			Video.create(userId, gridId, originalKey, geom, durationSec, recordedAt, visibility));

		copyToOriginal(s3Key, originalKey);
		videoRepository.upsertUserGrid(userId, gridId, video.getId());

		// 뱃지 지급 훅 (MSG-239): 업로드와 같은 트랜잭션에서 동기 판정하고, 새로 획득한 뱃지를 응답에 실어
		// FE 가 획득 연출을 하게 한다. 전 종류를 훑지 않고 "이 행동으로 딸 수 있는 종류"만 판정한다 —
		// 업로드는 항상 업로드 수 뱃지, 처음 수집한 격자면 총 격자 수·행정동 수집률 뱃지를 추가 판정.
		// metric 계산은 뱃지 도메인 몫이라 여기서는 행동 단위 호출만 한다. 상세 결정: docs/spec/MSG-239.md §D3~D5.
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
		// 핫스코어 (MSG-233): 커밋 후 증분 — 롤백 시 유령 증분 방지. 실패는 구현이 삼킨다 (FR-6).
		afterCommit(() -> hotScoreCommandService.recordUpload(gridId));
		videoEncodingJobRepository.enqueue(video.getId(), originalKey);

		return new ConfirmedVideo(video, !alreadyOccupied, List.copyOf(newBadges));
	}

	/**
	 * 격자 표시명의 구역 부분 (MSG-341). 업로드 확정·재생 둘 다 격자 1건이라 여기서 리졸버를 받아 바로 쓴다 —
	 * 호출당 zones 로드 1회로 D-1 의 "요청당 상수 회"를 만족한다(목록 경로처럼 루프 밖으로 끌어낼 대상이 없다).
	 * 매칭 없으면 NONE 이라 호출부에 null 분기가 없다.
	 */
	private ZoneCellName zoneName(String gridId) {
		GridIndex index = GridEncoder.decode(gridId);
		return zoneNameQueryService.resolver().name(index.gridY(), index.gridX());
	}

	/** 격자 저장 라벨의 행정동 이름 — 무귀속(해상)이거나 grids row 부재면 null (MSG-341 D-6). */
	private String findRegionName(String gridId) {
		return videoRepository.findRegionNameByGridId(gridId).orElse(null);
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
		String replacedEncodedKey = video.getEncodedUrl();
		String replacedBlurredKey = video.getBlurredS3Key();
		String replacedThumbnailKey = video.getThumbnailUrl();

		video.replaceFile(originalKey, request.durationSec(), request.recordedAt());
		// 클레임 선행 (MSG-247 1R): original_s3_key 클레임(UPDATE)을 S3 복사보다 먼저 flush 한다.
		// 동시 이중 확정 직렬화는 2R 부터 confirmUpload 의 advisory lock 몫이고, 이 flush 는
		// "DB 클레임 없이 복사된 객체가 없다"는 순서 보장으로 남는다(무해 — 2R 에서 유지 결정).
		videoRepository.flush();
		copyToOriginal(request.s3Key(), originalKey);

		videoEncodingJobRepository.enqueue(videoId, originalKey);
		// 파생 키가 인코딩 시도별로 갈리므로 이전 시도의 파일도 전부 참조를 잃는다 (MSG-67).
		afterCommit(() -> deleteQuietly(
			replacedKey, replacedEncodedKey, replacedBlurredKey, replacedThumbnailKey));
		return VideoReplaceResponseDto.from(video);
	}

	@Override
	@Transactional
	public VideoVisibilityResponseDto setVisibility(long userId, long videoId, VideoVisibilityRequestDto request) {
		Video video = findOwnedVideo(userId, videoId);
		if (video.isDeleted()) {
			throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);   // 지운 영상은 공개로 되살리지 않는다
		}
		// 행사 영상은 PUBLIC 고정이라 전환 자체를 막는다 (MSG-438 확정, MSG-440 구현). 피드·위치별 영상
		// 수·상세가 전부 PUBLIC 게이트라, 전환을 허용하면 올린 본인만 자기 영상을 행사방에서 잃는다.
		// 존재·소유권 판정 뒤에 두어 기존 에러 우선순위(3404 → 3403 → 그 밖)를 유지한다.
		if (eventVideoRepository.existsById(videoId)) {
			throw new ApiException(VideoErrorCode.EVENT_VIDEO_VISIBILITY_FIXED);
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
			throw new ApiException(VideoErrorCode.INVALID_COORDINATE);   // lat/lng 은 쌍으로만
		}
		if (!request.hasCoordinate()) {
			return;
		}
		validateCoordinate(request.lat(), request.lng());
		if (!GridEncoder.encode(request.lat(), request.lng()).equals(video.getGridId())) {
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
		afterCommit(() -> {
			deleteQuietly(
				video.getOriginalS3Key(), video.getEncodedUrl(), video.getThumbnailUrl(),
				video.getBlurredS3Key());
		});

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

	@Override
	public PresignedUrlResponseDto issuePresignedUrl(long userId, PresignedUrlRequestDto request) {
		String extension = request.extension().toLowerCase();
		String allowedType = ALLOWED_TYPES.get(extension);
		if (allowedType == null || !allowedType.equals(request.contentType())) {
			throw new ApiException(VideoErrorCode.UNSUPPORTED_EXTENSION);
		}
		// 선분석 원본만 전용 상한 (MSG-351 D-4) — 그 외(null 포함)는 기존 100MB 그대로다.
		// 미지의 purpose 값은 DTO @Pattern 이 컨트롤러 @Valid 단계에서 400 으로 거른다.
		long maxUploadBytes = "HIGHLIGHT_PREVIEW".equals(request.purpose())
			? awsProperties.s3().maxHighlightUploadBytes()
			: awsProperties.s3().maxUploadBytes();
		if (request.contentLength() > maxUploadBytes) {
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

	/**
	 * 친구 격자 영상 목록 (MSG-187 D5). 공개범위·상태 게이트와 정렬은 repository 쿼리가 정본이고,
	 * 여기서는 항목 presign 과 DTO 매핑만 한다 (getGridVideos 템플릿 동형).
	 * 친구 판정은 호출자(FriendServiceImpl) 선행 책임이라 여기서 관계를 다시 확인하지 않는다.
	 */
	@Override
	@Transactional(readOnly = true)
	public List<FriendGridVideoResponseDto> getFriendGridVideos(long ownerUserId, String gridId) {
		return videoRepository.findFriendGridVideos(ownerUserId, gridId)
			.stream()
			.map(video -> FriendGridVideoResponseDto.of(video, thumbnailUrlPresigner.presign(video.getThumbnailUrl())))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public GridCoverVideoResponseDto getGridCover(String gridId) {
		// 닉네임(MSG-371)은 대표가 있을 때만 1회 조회한다 — 대표 없는 격자(data null)는 조회가 아예 안 돈다.
		// 닉네임이 빈손이면 그 영상이 두 문장 사이(READ COMMITTED)에 탈퇴로 연쇄 삭제된 것이라 대표 없음으로
		// 떨어뜨린다(flatMap) — 대표가 원래 없는 격자와 같은 응답이고, nickname 이 null 로 실리는 경로가 없다.
		return videoRepository.findGlobalCover(gridId)
			.flatMap(video -> videoRepository.findAuthorNickname(video.getUserId())
				.map(nickname -> GridCoverVideoResponseDto.of(
					video, thumbnailUrlPresigner.presign(video.getThumbnailUrl()), nickname)))
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
		Map<Long, String> nicknames = authorNicknames(pageRows);
		// 배치 맵에 작성자가 없다 = 그 영상이 두 문장 사이(READ COMMITTED)에 탈퇴로 연쇄 삭제됐다 —
		// 항목을 빼서 숨긴다(MSG-371). nickname 이 null 로 실리는 경로가 없어진다.
		List<GridGlobalVideoResponseDto> videos = pageRows.stream()
			.filter(video -> nicknames.containsKey(video.getUserId()))
			.map(video -> GridGlobalVideoResponseDto.of(video, thumbnailUrlPresigner.presign(video.getThumbnailUrl()),
				nicknames.get(video.getUserId())))
			.toList();
		String nextCursor = null;
		if (hasNext) {
			// 커서는 걸러내기 전 pageRows 의 마지막 행 기준 그대로다 — 숨긴 항목이 페이지 끝이었을 때
			// 커서가 그 자리에 멈춰 같은 페이지를 무한히 다시 읽는 걸 막는다 (커서 규칙 무변경).
			Video last = pageRows.get(pageRows.size() - 1);
			nextCursor = VideoCursor.encode(gridId, last.getViewCount(), last.getCreatedAt(), last.getId());
		}
		return new GridVideoPageResponseDto(videos, hasNext, nextCursor);
	}

	/**
	 * 미션 영상 목록 조회 (MSG-390). userId 없음 — 후보가 미션·격자·기간으로만 정해져 결과가 호출자와
	 * 무관하다(내 PRIVATE·FRIENDS 영상도 나오지 않는다). 후보 술어·정렬은 repository 가 정본이고, 여기서는
	 * getGridGlobalVideos 와 같은 순서로 size 클램프 → lookahead(size+1) 조회 → hasNext 판정·트림 →
	 * 닉네임 배치 → 항목 presign → nextCursor 발급만 한다. 클램프 상수를 격자 목록과 공유하는 것은
	 * 두 목록의 페이지 규격이 같아서다(§API 명세).
	 */
	@Override
	@Transactional(readOnly = true)
	public GridVideoPageResponseDto getMissionVideos(long missionId, String cursor, int size) {
		int pageSize = size < 1 ? GLOBAL_PAGE_DEFAULT_SIZE : Math.min(size, GLOBAL_PAGE_MAX_SIZE);
		List<Video> rows = queryMissionPage(missionId, cursor, pageSize + 1);
		boolean hasNext = rows.size() > pageSize;
		List<Video> pageRows = hasNext ? rows.subList(0, pageSize) : rows;
		Map<Long, String> nicknames = authorNicknames(pageRows);
		// 배치 맵에 작성자가 없다 = 그 영상이 두 문장 사이(READ COMMITTED)에 탈퇴로 연쇄 삭제됐다 —
		// 항목을 빼서 숨긴다(MSG-371 규칙 그대로). nickname 이 null 로 실리는 경로가 없다.
		List<GridGlobalVideoResponseDto> videos = pageRows.stream()
			.filter(video -> nicknames.containsKey(video.getUserId()))
			.map(video -> GridGlobalVideoResponseDto.of(video, thumbnailUrlPresigner.presign(video.getThumbnailUrl()),
				nicknames.get(video.getUserId())))
			.toList();
		String nextCursor = null;
		if (hasNext) {
			// 커서는 걸러내기 전 pageRows 의 마지막 행 기준이다 — 숨긴 항목이 페이지 끝일 때 커서가 그
			// 자리에 멈춰 같은 페이지를 무한히 다시 읽는 걸 막는다(격자 목록과 같은 규칙).
			Video last = pageRows.get(pageRows.size() - 1);
			nextCursor = MissionVideoCursor.encode(missionId, last.getRecordedAt(), last.getId());
		}
		return new GridVideoPageResponseDto(videos, hasNext, nextCursor);
	}

	/**
	 * 격자 전역 시간대 분포 조회 (MSG-372). 게이트·KST 변환은 repository 쿼리가 정본이고, 여기서는
	 * 업로드가 있는 시간대만 오는 GROUP BY 결과를 24구간에 얹는 채움만 한다 — 어떤 격자든(공개 영상 0건·
	 * 존재하지 않는 gridId 포함) 0시부터 23시까지 24개가 전부 실린다. 저장 존은 호출자 바인딩 관례라
	 * 여기서 UTC 를 넘긴다(MSG-376). 시각을 새로 만들지 않아 Clock 은 쓰지 않는다.
	 */
	@Override
	@Transactional(readOnly = true)
	public GridHourlyUploadResponseDto getGridHourlyUploads(String gridId) {
		long[] counts = new long[HOURS_PER_DAY];
		for (HourlyUploadProjection row : videoRepository.countHourlyUploadsByGrid(gridId, ZoneOffset.UTC.getId())) {
			counts[row.getHour()] = row.getCount();
		}
		List<HourlyUploadCountResponseDto> hours = IntStream.range(0, HOURS_PER_DAY)
			.mapToObj(hour -> new HourlyUploadCountResponseDto(hour, counts[hour]))
			.toList();
		return new GridHourlyUploadResponseDto(gridId, hours);
	}

	/**
	 * 페이지 항목의 작성자 닉네임 배치 조회 (MSG-371). 트림이 끝난 pageRows 로만 부른다 — 잘려나간
	 * lookahead 행의 작성자는 애초에 들어오지 않는다. 중복 제거한 id 로 IN 1회라 페이지 크기와 무관하게
	 * 목록 조회의 DB 왕복이 2회(영상 + 닉네임)로 고정된다. 빈 페이지는 조회를 건너뛴다(IN 빈 목록 회피).
	 */
	private Map<Long, String> authorNicknames(List<Video> pageRows) {
		Set<Long> userIds = pageRows.stream().map(Video::getUserId).collect(Collectors.toSet());
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return videoRepository.findAuthorNicknames(userIds).stream()
			.collect(Collectors.toMap(AuthorNicknameProjection::getUserId, AuthorNicknameProjection::getNickname));
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

	private List<Video> queryMissionPage(long missionId, String cursor, int limit) {
		if (cursor == null) {
			return videoRepository.findMissionVideos(missionId, limit);
		}
		MissionVideoCursor decoded = decodeMissionCursor(cursor);
		if (decoded.missionId() != missionId) {
			// 다른 미션에서 발급된 커서 — 경계값이 이 미션의 keyset 으로 오적용돼 결과가 조용히 잘리는 걸
			// 막는다(격자 커서의 gridId 바인딩과 같은 규칙). 형식 위반과 같은 무효 커서로 취급한다.
			throw new ApiException(VideoErrorCode.INVALID_CURSOR);
		}
		return videoRepository.findMissionVideosAfter(missionId, decoded.recordedAt(), decoded.id(), limit);
	}

	/**
	 * 무효 커서는 400 으로 거른다 — 형식 위반과 저장 가능 범위 밖 시각(IllegalArgumentException), 시각 복원
	 * 자체가 실패하는 극단값(DateTimeException)이 전부 RuntimeException 이라 한 번에 잡힌다. 범위 검증이
	 * 디코드 안에 있어야 하는 이유는, 그 시각이 쿼리까지 흘러가면 바인딩 단계에서 깨져 이 try 밖의 공통
	 * 500 이 되기 때문이다.
	 */
	private MissionVideoCursor decodeMissionCursor(String cursor) {
		try {
			return MissionVideoCursor.decode(cursor);
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
	public VideoPlaybackResponseDto getVideoPlayback(Long userId, long videoId) {
		Video video = videoRepository.findById(videoId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		// userId 는 비로그인이면 null 이다(MSG-491). 익명은 소유자일 수 없고 친구일 수도 없으므로 아래 두
		// 판정에서 각각 false 로 떨어져 PUBLIC 만 통과한다.
		// equals 로 비교하는 이유: 양쪽 다 Long 이라 == 는 값이 아니라 참조를 본다(Long 캐시 밖 id, 즉
		// 127 초과에서 소유자 판정이 조용히 무너진다 — VideoBlindIntegrationTest 가 잡은 실제 회귀).
		boolean owner = userId != null && userId.equals(video.getUserId());

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
				case FRIENDS -> userId != null && friendshipQueryService.isFriend(video.getUserId(), userId);
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

		// 6. 격자 표시명 (MSG-341). 재생 경로엔 좌표가 없어 gridId 가 유일한 입력이다 — 구역은 gridId 디코드
		// 산술로, 행정동은 격자 저장 라벨 조회로 얻는다(D-6). 접근이 거부된 요청은 위에서 이미 던져졌으므로
		// 이름 계산·조회는 응답을 실제로 내려주는 경로에서만 돈다.
		ZoneCellName zoneCellName = zoneName(video.getGridId());
		// 7. 작성자 닉네임 (MSG-371) — 접근 제어를 다 통과한 뒤 1회. 거부된 요청에선 돌지 않는다.
		//    소유자 본인 조회에도 실린다(본인 닉네임). 빈손이면 그 영상이 위 findById 이후(READ COMMITTED)
		//    탈퇴로 연쇄 삭제된 것이라 1번 DELETED 분기와 같은 404 로 수렴한다 — 신규 에러코드 없음.
		String nickname = videoRepository.findAuthorNickname(video.getUserId())
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		// viewCount 는 증가 전 스냅샷 — native UPDATE 는 로드된 엔티티 필드를 건드리지 않는다(§설계 M7).
		return VideoPlaybackResponseDto.of(video, playbackUrl, thumbnailUrl, expiresInSec,
			zoneCellName.zoneName(), zoneCellName.zoneCell(), findRegionName(video.getGridId()), nickname);
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
	 * 미션 경유 업로드가 미션 조회보다 앞에서 쓰는 순수 검증 (MSG-459 D-10) — 확정 코어가 도는 검사 중
	 * S3 도 DB 도 건드리지 않는 둘만 골라 먼저 돌린다. 확정이 나중에 같은 검사를 다시 해도 무해하다.
	 */
	@Override
	public void validateUploadRequest(long userId, String s3Key, LocalDateTime recordedAt) {
		validateRecordedAt(recordedAt);
		validatePendingKey(userId, s3Key);
	}

	/**
	 * pending 키가 형식에 맞고 내 접두어를 달았는지 보고 클레임 prefix 를 돌려준다 (MSG-459 에서 추출).
	 * 확정(confirmUpload)과 선행 검증(validateUploadRequest)이 같은 정의를 봐야, 앞에서 통과한 키가
	 * 뒤에서 3401 로 떨어지는 어긋남이 생기지 않는다.
	 */
	private String validatePendingKey(long userId, String pendingKey) {
		if (!pendingKey.startsWith("%s%d/".formatted(PENDING_PREFIX, userId))) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		return claimPrefix(pendingKey).orElseThrow(() -> new ApiException(VideoErrorCode.INVALID_S3_KEY));
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
		// 형식·소유 접두어 검사가 먼저다 — 그 검사를 지나야 아래 substring 이 안전하다.
		String claimPrefix = validatePendingKey(userId, pendingKey);
		String stem = pendingKey.substring(PENDING_PREFIX.length());   // "{userId}/{uuid}.{ext}"
		int extAt = stem.lastIndexOf('.');

		videoRepository.acquirePendingKeyConfirmLock(pendingKey);
		// 영상 1개로 좌표만 바꿔가며 무한 점령하는 걸 막는다(이중 확정 차단). prefix 매치가 시도별 키를,
		// 정확 매치가 구형 결정 키를 잡는다. StartingWith 의 LIKE 와일드카드는 매치를 넓힐 뿐이라
		// 거부 방향으로만 오작동 가능 — 이중 확정 우회로는 악용될 수 없다.
		if (videoRepository.existsByOriginalS3KeyStartingWith(claimPrefix)
			|| videoRepository.existsByOriginalS3Key(ORIGINAL_PREFIX + stem)) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		HeadObjectResponse head = requireObjectExists(pendingKey);
		// 실측 크기 재검증 (MSG-351 교차 리뷰 P1-1): presign 상한은 발급 시점 선언값만 묶는데, 선분석용
		// HIGHLIGHT_PREVIEW presign(2GiB)의 키 형태가 일반 업로드와 같아 그 키로 확정하면 100MB 상한이
		// 우회된다. 존재 확인에 이미 쓴 headObject 응답의 실제 크기로 확정 시점에 다시 막는다 —
		// saveVideo·replaceVideo 가 이 메서드를 공유하므로 두 경로 다 닫힌다. 추가 S3 호출 없음.
		Long contentLength = head.contentLength();
		if (contentLength != null && contentLength > awsProperties.s3().maxUploadBytes()) {
			throw new ApiException(VideoErrorCode.FILE_TOO_LARGE);
		}
		requireVideoContainer(pendingKey, contentLength);
		return claimPrefix + UUID.randomUUID() + stem.substring(extAt);
	}

	/**
	 * 확정 시점 내용 검증 (MSG-392) — 앞 4KB 만 읽어 ISO BMFF 컨테이너 구조인지 본다. 여기서 거부하면
	 * 아무 것도 쓰기 전이라 점령·뱃지·스트릭·미션 스탬프가 하나도 남지 않는다(fail-closed).
	 * saveVideo·confirmAtGrid(행사·미션)·replaceVideo 가 confirmUpload 를 공유하므로 네 경로가 함께 닫힌다.
	 */
	private void requireVideoContainer(String pendingKey, Long contentLength) {
		// 박스 헤더 하나도 못 담는 객체(빈 객체·1바이트 치팅 파일)는 본문을 읽을 가치가 없다 — S3 호출 0회.
		// contentLength 가 null 이면 지름길을 건너뛰고 범위 요청으로 판정한다 (크기 재검증의 null 처리와 같은 모양).
		if (contentLength != null && contentLength < SIGNATURE_MIN_BYTES) {
			throw new ApiException(VideoErrorCode.NOT_A_VIDEO_FILE);
		}
		byte[] headBytes = readObjectHead(pendingKey);
		// 창 소진과 파일 끝(EOF)은 다른 사건이라 결과가 갈린다. contentLength 를 먼저 쓰는 이유는, 받은
		// 바이트 수로만 판정하면 객체 크기가 정확히 4096 일 때 창 소진으로 오분류돼 잘린 구조가 통째로
		// 빠져나가기 때문이다.
		boolean wholeObject = contentLength != null
			? contentLength <= SIGNATURE_HEAD_BYTES
			: headBytes.length < SIGNATURE_HEAD_BYTES;
		if (!VideoSignature.looksLikeVideoContainer(headBytes, wholeObject)) {
			throw new ApiException(VideoErrorCode.NOT_A_VIDEO_FILE);
		}
	}

	/**
	 * 객체 앞부분 범위 요청 — 파일을 내려받지 않으므로 100MB 든 1MB 든 전송량이 같다.
	 * 실패 갈래는 셋이다. 404 는 존재 확인과 같은 3402, 416 은 3428, 나머지 S3·인프라 오류는 전파해
	 * 공통 핸들러가 500 으로 바꾸게 둔다 — 장애를 "영상 파일이 아닙니다"로 잘못 안내하지 않기 위해서다.
	 */
	private byte[] readObjectHead(String pendingKey) {
		try {
			return s3Client.getObjectAsBytes(GetObjectRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.key(pendingKey)
				.range("bytes=0-" + (SIGNATURE_HEAD_BYTES - 1))
				.overrideConfiguration(o -> o.apiCallTimeout(SIGNATURE_READ_TIMEOUT))
				.build()).asByteArray();
		} catch (NoSuchKeyException e) {
			// headObject 와 이 호출 사이에 키가 지워진 경합 — 존재 확인이 같은 사건에 쓰는 코드로 맞춘다.
			// 빈 객체 덮어쓰기(416)와 같은 창의 변종인데 한쪽만 500 이 되면 정상 경합이 서버 결함으로 보인다.
			throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
		} catch (S3Exception e) {
			if (e.statusCode() == 404) {
				throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
			}
			// 빈 객체에 범위를 걸면 416 이다. 위 지름길 덕에 정상 흐름에선 나올 수 없다.
			if (e.statusCode() == 416) {
				throw new ApiException(VideoErrorCode.NOT_A_VIDEO_FILE, e);
			}
			throw e;
		}
	}

	/**
	 * pending 키에서 파생되는 original 클레임 prefix — 확정(쓰기)과 멱등 재시도 판정(읽기)이 이 규칙 하나를
	 * 공유한다 (MSG-440). 규칙이 갈라지면 재시도가 자기 확정을 못 찾아 영상이 하나 더 생긴다.
	 * 형식이 어긋난 키(pending prefix 아님·확장자 없음)는 empty 다 — 확정은 이것으로 3401 을 던지고,
	 * 재시도 판정은 "찾은 게 없다"로 받아 뒤따르는 확정이 같은 3401 을 내게 한다.
	 */
	private Optional<String> claimPrefix(String pendingKey) {
		if (!pendingKey.startsWith(PENDING_PREFIX)) {
			return Optional.empty();
		}
		String stem = pendingKey.substring(PENDING_PREFIX.length());
		int extAt = stem.lastIndexOf('.');
		return extAt < 0
			? Optional.empty()
			: Optional.of(ORIGINAL_PREFIX + stem.substring(0, extAt) + "-");
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

	/** 존재하면 headObject 응답을 돌려준다 — 호출자(confirmUpload)가 실측 크기 검증에 재사용한다 (P1-1). */
	private HeadObjectResponse requireObjectExists(String s3Key) {
		try {
			return s3Client.headObject(HeadObjectRequest.builder()
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
