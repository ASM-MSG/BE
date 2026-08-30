package com.msg.fillmap.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.dto.EventLocationVideoPageResponseDto;
import com.msg.fillmap.event.dto.EventLocationVideoResponseDto;
import com.msg.fillmap.event.dto.EventVideoDetailResponseDto;
import com.msg.fillmap.event.dto.EventVideoUploadRequestDto;
import com.msg.fillmap.event.dto.EventVideoUploadResponseDto;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventStatus;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventLocationVideoRow;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.event.support.EventVideoCursor;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.service.ConfirmedVideo;
import com.msg.fillmap.video.service.VideoService;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

/**
 * 행사 영상 구현 (MSG-440). 업로드는 새 파이프라인이 아니라 기존 업로드 확정 코어를 좌표 대신 지정 격자로
 * 태우고 event_videos 연결 한 줄을 같은 트랜잭션으로 얹는 것이다 — 점령·뱃지·스트릭·인코딩이 일반 업로드와
 * 같아야 한다는 2026-08-20 확정의 귀결이고, 유일한 예외가 미션 비연계다.
 */
@Service
public class EventVideoServiceImpl implements EventVideoService {

	/** 피드 페이지 크기 — 격자·미션 영상 목록과 같은 규격이다. 범위 밖은 에러가 아니라 클램프(MSG-237 §D5). */
	private static final int PAGE_DEFAULT_SIZE = 20;
	private static final int PAGE_MAX_SIZE = 50;

	private final EventOccurrenceRepository occurrenceRepository;
	private final EventLocationRepository locationRepository;
	private final EventVideoRepository eventVideoRepository;
	private final VideoService videoService;
	// 상세의 작성자 닉네임·행정동 라벨 조회 (MSG-371·MSG-341 선례). 같은 Owner B 내부 의존이다.
	private final VideoRepository videoRepository;
	private final ThumbnailUrlPresigner thumbnailUrlPresigner;
	private final ZoneNameQueryService zoneNameQueryService;
	private final EntityManager entityManager;
	// 상세의 반응 네 필드와 피드 카드의 두 수를 읽는다 (MSG-441). 같은 도메인 안의 읽기 전용 의존이다.
	private final EventVideoInteractionService interactionService;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — clock 을 Clock.systemUTC() 로 고정해 전체 생성자로 위임한다 (EventQueryServiceImpl
	 * 선례). UTC 인 이유는 starts_at·ends_at 이 UTC 로 저장되기 때문이다 — 기본존(로컬 KST)이면 업로드 창
	 * 판정이 9시간 어긋난다. 전체 생성자는 창 경계 검증용 고정 클럭 주입에 쓴다.
	 */
	@Autowired
	public EventVideoServiceImpl(
		EventOccurrenceRepository occurrenceRepository,
		EventLocationRepository locationRepository,
		EventVideoRepository eventVideoRepository,
		VideoService videoService,
		VideoRepository videoRepository,
		ThumbnailUrlPresigner thumbnailUrlPresigner,
		ZoneNameQueryService zoneNameQueryService,
		EntityManager entityManager,
		EventVideoInteractionService interactionService
	) {
		this(occurrenceRepository, locationRepository, eventVideoRepository, videoService, videoRepository,
			thumbnailUrlPresigner, zoneNameQueryService, entityManager, interactionService, Clock.systemUTC());
	}

	public EventVideoServiceImpl(
		EventOccurrenceRepository occurrenceRepository,
		EventLocationRepository locationRepository,
		EventVideoRepository eventVideoRepository,
		VideoService videoService,
		VideoRepository videoRepository,
		ThumbnailUrlPresigner thumbnailUrlPresigner,
		ZoneNameQueryService zoneNameQueryService,
		EntityManager entityManager,
		EventVideoInteractionService interactionService,
		Clock clock
	) {
		this.occurrenceRepository = occurrenceRepository;
		this.locationRepository = locationRepository;
		this.eventVideoRepository = eventVideoRepository;
		this.videoService = videoService;
		this.videoRepository = videoRepository;
		this.thumbnailUrlPresigner = thumbnailUrlPresigner;
		this.zoneNameQueryService = zoneNameQueryService;
		this.entityManager = entityManager;
		this.interactionService = interactionService;
		this.clock = clock;
	}

	/**
	 * 판정 순서가 계약이다 (§API 1): 회차 존재·노출 → 위치 잠금·회차 정합 → 회차 재독 → 멱등 재시도 →
	 * 업로드 창 → 확정 코어 → event_videos 연결. 멱등 판정이 창 판정보다 앞선 이유는, 마감 직전 커밋된
	 * 업로드의 응답이 유실되고 재시도가 마감 후 도착했을 때 이미 성공한 요청이 13409 로 거절되면 안 되기
	 * 때문이다(Codex 교차 리뷰 P2). 전체가 한 트랜잭션이다 — 영상 생성과 대표 격자 연결은 함께 커밋된다.
	 */
	@Override
	@Transactional
	public EventVideoUploadResponseDto upload(long userId, long occurrenceId, long locationId,
		EventVideoUploadRequestDto request) {
		LocalDateTime now = LocalDateTime.now(clock);
		EventOccurrence occurrence = occurrenceRepository.findById(occurrenceId)
			.filter(found -> isVisible(found, now))
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_NOT_FOUND));
		EventLocation location = locationRepository.findWithLockById(locationId)
			.filter(found -> found.getOccurrence().getId().equals(occurrenceId))
			// 중지된 위치에는 올릴 수 없다 (MSG-500 D-3) — 없는 위치와 같은 404 로 수렴한다.
			.filter(found -> found.getHiddenAt() == null)
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_LOCATION_NOT_FOUND));
		// 잠금 획득 후 회차 재독 — 위 조회는 잠금이 없어, 잠금 대기 사이 부팅 리시드(롤링 배포 중 새
		// 인스턴스의 멱등 UPSERT)가 일정을 바꾸면 영속성 컨텍스트에 캐시된 startsAt·endsAt 이 낡은 값으로
		// 남는다. 재조회는 1차 캐시가 같은 인스턴스를 돌려주므로 refresh 여야 한다(Codex 2R).
		entityManager.refresh(occurrence);

		// 멱등 재시도 판정 — 저장 컬럼을 새로 만들지 않고 pending 키 클레임(videos.original_s3_key)을 쓴다.
		Optional<Video> alreadyConfirmed = videoService.findConfirmedByPendingKey(request.s3Key());
		if (alreadyConfirmed.isPresent()) {
			return replay(userId, locationId, alreadyConfirmed.get());
		}
		// 창 판정(startsAt <= now < uploadClosesAt, 반개구간)은 442 가드가 단일 판정처다 — now 를 넘겨
		// 잠금·refresh 뒤의 이 시각으로 판정하게 한다. 일반 격자 업로드(POST /api/videos)는 이 창과 무관하다.
		EventLifecycleGuard.checkUploadOpen(occurrence, now);

		ConfirmedVideo confirmed = videoService.confirmAtGrid(userId, location.getRepresentativeGridId(),
			request.s3Key(), request.durationSec(), request.recordedAt());
		// 회차와 위치의 정합(영상 회차 = 위치 회차)은 복합 FK 가 커밋 시점에 검증한다 (MSG-438 DDL).
		eventVideoRepository.save(new EventVideo(confirmed.video(), location, occurrenceId));
		Video video = confirmed.video();
		return new EventVideoUploadResponseDto(video.getId(), video.getGridId(),
			video.getProcessingStatus().name(), confirmed.occupied(), confirmed.newBadges());
	}

	/**
	 * 위치별 영상 피드 (§API 2). 술어·정렬은 리포지토리가 정본이고 여기서는 size 클램프 →
	 * lookahead(size+1) 조회 → hasNext 판정·트림 → 썸네일 presign → nextCursor 발급만 한다
	 * (getGridGlobalVideos 와 같은 순서). 아카이브를 포함한 모든 노출 상태에서 조회되고 영상이 없으면
	 * 빈 페이지다 — 빈 상태 문구는 화면 몫이라 예외가 아니다.
	 */
	@Override
	@Transactional(readOnly = true)
	public EventLocationVideoPageResponseDto getLocationVideos(long occurrenceId, long locationId, String cursor,
		int size) {
		LocalDateTime now = LocalDateTime.now(clock);
		occurrenceRepository.findById(occurrenceId)
			.filter(found -> isVisible(found, now))
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_NOT_FOUND));
		locationRepository.findById(locationId)
			.filter(found -> found.getOccurrence().getId().equals(occurrenceId))
			// 중지된 위치의 영상 목록도 없는 위치와 같은 404 다 (MSG-500 D-3).
			.filter(found -> found.getHiddenAt() == null)
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_LOCATION_NOT_FOUND));

		int pageSize = size < 1 ? PAGE_DEFAULT_SIZE : Math.min(size, PAGE_MAX_SIZE);
		List<EventLocationVideoRow> rows = queryPage(locationId, cursor, pageSize + 1);
		boolean hasNext = rows.size() > pageSize;
		List<EventLocationVideoRow> pageRows = hasNext ? rows.subList(0, pageSize) : rows;
		// 반응 수는 이 페이지의 영상 id 집합으로 도는 group by 두 번이다 — 항목마다 세면 N+1 이다.
		EventVideoReactionCounts reactions = interactionService.countReactions(
			pageRows.stream().map(EventLocationVideoRow::videoId).toList());
		List<EventLocationVideoResponseDto> videos = pageRows.stream()
			.map(row -> new EventLocationVideoResponseDto(row.videoId(),
				thumbnailUrlPresigner.presign(row.thumbnailKey()), row.durationSec(), row.createdAt(),
				reactions.helpfulCount(row.videoId()), reactions.commentCount(row.videoId())))
			.toList();
		String nextCursor = null;
		if (hasNext) {
			EventLocationVideoRow last = pageRows.get(pageRows.size() - 1);
			nextCursor = EventVideoCursor.encode(locationId, last.createdAt(), last.videoId());
		}
		return new EventLocationVideoPageResponseDto(videos, hasNext, nextCursor);
	}

	private List<EventLocationVideoRow> queryPage(long locationId, String cursor, int limit) {
		// 정렬 없는 PageRequest — 정렬은 쿼리의 ORDER BY 고정이라 Pageable 이 덧붙이면 안 된다.
		Pageable page = PageRequest.of(0, limit);
		if (cursor == null) {
			return eventVideoRepository.findVisibleByLocationId(locationId, page);
		}
		EventVideoCursor decoded = decodeCursor(cursor);
		if (decoded.locationId() != locationId) {
			// 다른 위치에서 발급된 커서 — 경계값이 이 위치의 keyset 으로 오적용돼 결과가 조용히 잘리는 걸
			// 막는다(VideoCursor 의 격자 바인딩과 같은 규칙). 형식 위반과 같은 무효 커서로 취급한다.
			throw new ApiException(EventErrorCode.INVALID_CURSOR);
		}
		return eventVideoRepository.findVisibleByLocationIdAfter(
			locationId, decoded.createdAt(), decoded.id(), page);
	}

	/**
	 * 무효 커서는 조용히 첫 페이지로 폴백하지 않고 400 으로 거른다 — FE 버그가 무한 첫 페이지 루프로
	 * 은폐되는 걸 막는다. 형식 위반·범위 밖 시각이 전부 RuntimeException 이라 한 번에 잡힌다.
	 */
	private EventVideoCursor decodeCursor(String cursor) {
		try {
			return EventVideoCursor.decode(cursor);
		} catch (RuntimeException e) {
			throw new ApiException(EventErrorCode.INVALID_CURSOR, e);
		}
	}

	/**
	 * 행사 영상 상세 (§API 3). readOnly 가 아닌 이유는 조회수 증가 UPDATE 다.
	 * 노출 술어는 피드·위치별 영상 수와 같은 정의라 목록에서 보인 것만 상세가 열리고 그 역도 성립한다 —
	 * 소유자 본인도 예외가 아니다(READY 전 본인 영상 확인은 GET /api/videos/{videoId} 몫).
	 * 재생본 선택(blurred 우선, 없으면 encoded)과 조회수 규칙(발급했고 소유자가 아닐 때만 +1)은 기존 재생
	 * 경로와 같다 — 행사 영상은 전역 격자 피드에도 노출되므로 두 경로의 지표 기준을 가를 이유가 없다.
	 */
	@Override
	@Transactional
	public EventVideoDetailResponseDto getVideoDetail(long videoId, Long userId) {
		LocalDateTime now = LocalDateTime.now(clock);
		EventVideo link = eventVideoRepository.findById(videoId)
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_VIDEO_NOT_FOUND));
		Video video = link.getVideo();
		if (!isVisible(video)) {
			throw new ApiException(EventErrorCode.EVENT_VIDEO_NOT_FOUND);
		}
		EventLocation location = link.getLocation();
		// 중지된 위치의 영상은 알던 videoId 로도 열리지 않는다 (MSG-500 D-3) — 기존 존재 은닉과 같은 404 다.
		if (location.getHiddenAt() != null) {
			throw new ApiException(EventErrorCode.EVENT_VIDEO_NOT_FOUND);
		}
		EventOccurrence occurrence = location.getOccurrence();
		// 정상 데이터에선 도달하지 않지만(노출 전 회차엔 영상이 없다) 은닉 판정을 명시해 일관성을 지킨다.
		if (!isVisible(occurrence, now)) {
			throw new ApiException(EventErrorCode.EVENT_VIDEO_NOT_FOUND);
		}

		String playbackKey = video.getBlurredS3Key() != null ? video.getBlurredS3Key() : video.getEncodedUrl();
		String playbackUrl = thumbnailUrlPresigner.presign(playbackKey);
		boolean owner = userId != null && userId.equals(video.getUserId());
		if (playbackUrl != null && !owner) {
			videoRepository.incrementViewCount(videoId);
		}
		// 빈손이면 그 영상이 위 조회 이후(READ COMMITTED) 탈퇴로 연쇄 삭제된 것이라 존재 은닉 404 로 수렴한다.
		String nickname = videoRepository.findAuthorNickname(video.getUserId())
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_VIDEO_NOT_FOUND));
		EventStatus status = occurrence.statusAt(now);
		EventVideoDetailReactions reactions = interactionService.getDetailReactions(videoId, userId);
		String gridId = location.getRepresentativeGridId();
		GridIndex index = GridEncoder.decode(gridId);
		ZoneCellName zone = zoneNameQueryService.resolver().name(index.gridY(), index.gridX());
		return new EventVideoDetailResponseDto(
			videoId,
			occurrence.getId(),
			status.name(),
			location.getId(),
			location.getName(),
			gridId,
			zone.zoneName(),
			zone.zoneCell(),
			videoRepository.findRegionNameByGridId(gridId).orElse(null),
			playbackUrl,
			video.getDurationSec(),
			video.getRecordedAt(),
			video.getCreatedAt(),
			nickname,
			// 아카이브 전환(종료 + 30일)부터 댓글·도움돼요 입력을 잠근다(FR-13, 2026-08-21 정책 번복 —
			// 유예 기간은 열려 있다). 표시와 집행(EventLifecycleGuard.checkInteractionOpen)이 갈리면 안 되므로
			// 둘 다 회차 상태를 축으로 보고, 경계 정각의 해석은 statusAt 이 정본이다.
			status == EventStatus.ARCHIVED,
			reactions.helpfulCount(),
			reactions.helpfulByMe(),
			reactions.commentCount(),
			reactions.comments());
	}

	/**
	 * 노출 술어 — 삭제·블라인드(ACTIVE 아님), 비공개(PUBLIC 아님), 처리 미완료(READY 아님)를 걷어낸다.
	 * 피드·위치별 영상 수 쿼리의 WHERE 와 같은 정의이며, 표현이 갈라지면 숫자와 목록의 불일치가 곧 존재
	 * 노출이 된다(§도메인 "술어는 한 곳").
	 */
	private boolean isVisible(Video video) {
		return video.getStatus() == VideoStatus.ACTIVE
			&& video.getVisibility() == Visibility.PUBLIC
			&& video.getProcessingStatus() == ProcessingStatus.READY;
	}

	/**
	 * 응답이 유실된 첫 시도의 재전송 (§API 1 멱등 키). 저장 행 기준으로 성공을 되돌려주되 occupied 는 false,
	 * newBadges 는 빈 배열이다 — 재시도 시점에 첫 응답을 복원할 저장 컬럼이 없기 때문이며, 유실된 뱃지
	 * 연출은 뱃지 목록·알림으로 이미 노출된다. processingStatus 는 이 시점의 실제 값이라 첫 응답과 다를 수
	 * 있다(인코딩 진행). 확정 행은 있는데 소유자나 연결 위치가 다르면 남의 키를 확정하려는 시도이므로
	 * 기존 이중 확정 차단과 같은 3401 이다.
	 */
	private EventVideoUploadResponseDto replay(long userId, long locationId, Video video) {
		boolean sameLocation = eventVideoRepository.findById(video.getId())
			.map(link -> link.getLocation().getId().equals(locationId))
			.orElse(false);
		if (video.getUserId() != userId || !sameLocation) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		return new EventVideoUploadResponseDto(video.getId(), video.getGridId(),
			video.getProcessingStatus().name(), false, List.of());
	}

	/**
	 * 노출 판정 — 아직 노출 시작 전인 예정 회차만 감춘다.
	 * ponytail: EventQueryServiceImpl.isVisible 의 쌍둥이다. 공통 승격은 2026-09-07 멘토 라이브 코드 리뷰
	 * 전 구조 변경 금지 합의로 유예했고(MSG-439 §API 1 의 validateBounds 와 같은 처리), 유예 조건은 판정이
	 * 글자 단위로 같게 유지되는 것이다 — 갈라지면 노출 전 행사의 존재가 한쪽 경로로 새어 나간다.
	 */
	private boolean isVisible(EventOccurrence occurrence, LocalDateTime now) {
		return occurrence.statusAt(now) != EventStatus.UPCOMING || !occurrence.getVisibleFrom().isAfter(now);
	}
}
