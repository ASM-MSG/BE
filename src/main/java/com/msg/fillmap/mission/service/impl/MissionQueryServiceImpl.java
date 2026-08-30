package com.msg.fillmap.mission.service.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.KoreaCoordinates;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridEncoder.GridRange;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.dto.GridMissionResponseDto;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto.SpotStats;
import com.msg.fillmap.mission.dto.MissionProgressResponseDto;
import com.msg.fillmap.mission.dto.MissionRegionAggregateResponseDto;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.dto.MissionShape;
import com.msg.fillmap.mission.dto.MissionShape.BoxShape;
import com.msg.fillmap.mission.dto.MissionShape.Cell;
import com.msg.fillmap.mission.dto.MissionShape.CellsShape;
import com.msg.fillmap.mission.dto.MissionShape.LatLng;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.dto.MissionShape.RegionShape;
import com.msg.fillmap.mission.dto.MissionShape.Spot;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionProgressProjection;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.video.repository.MissionTotalVideoCountProjection;
import com.msg.fillmap.video.repository.MissionVideoCountProjection;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 미션 조회 구현 (MSG-222 → MSG-398). 목록은 active 판정 → 유형별 shape 단일 분기 합성 → 1h 전역 캐시
 * 위에서 종류·뷰포트 메모리 필터(D1 — 스냅샷은 계속 전국 한 벌, 캐시 키 없음), 진행도는 캐시 없이 native
 * 집계 한 번이다(D8).
 *
 * 캐시는 더블체크 락 — 읽기 경로는 volatile 락프리, 만료 감지 시에만 synchronized 로 재계산을 직렬화한다.
 * 블록 안에서 만료를 재확인해 방금 다른 스레드가 갱신했으면 그 스냅샷을 재사용하므로, 동시 만료 시 세대 역전
 * (먼저 시작한 느린 쿼리가 새 스냅샷을 덮음)과 중복 DB 조회가 사라진다.
 *
 * ponytail: 전역 단일 홀더 — Spring Cache·Caffeine·Redis 스택은 시간당 1회·수백 행 쿼리에 과하다(§설계 D3).
 * 멀티 인스턴스로 가면 인스턴스별 홀더라 최대 1h 불일치 → 그때 Redis(TTL 1h)로 이관. 현재 dev 는 단일 인스턴스 전제.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class MissionQueryServiceImpl implements MissionQueryService {

	private static final Duration CACHE_TTL = Duration.ofHours(1);

	/**
	 * 투영 보정 (MSG-398 D3) — viewportRange 가 꼭짓점 네 점만 환산해 min/max 를 잡는데, 뷰포트가
	 * 중앙자오선(경도 127.5도)을 품으면 남쪽 변이 두 귀퉁이보다 아래로 처져(실측 최대 이탈 29.6m) 남쪽 한
	 * 행이 통째로 빠진다. 0.5도 상한(D6) 안에서 이탈이 셀 한 변(100m)보다 작아 1칸이면 반드시 덮는다 —
	 * 상한을 올리면 이 값도 다시 잰다. D4 의 노출 마진과 성격이 달라(정확도 보정 vs 노출 정책) 따로 둔다.
	 */
	private static final long PROJECTION_PAD_CELLS = 1;

	/**
	 * 진행도 벌크 조회 id 상한 (MSG-398 D7) — 목록이 낼 수 있는 최대 건수(0.5도 한 종류 최대 169건,
	 * 활성 전량으로도 한 종류 최대 195건)보다 넉넉히 크다. 조용히 잘라내면 잘린 카드가 "진행 0"으로
	 * 그려져 틀린 값이 화면에 남으므로 거절한다.
	 */
	private static final int MAX_PROGRESS_IDS = 300;

	// viewport 한 변의 최대 위경도 span(도). 초과 시 과도한 응답으로 보고 VIEWPORT_TOO_LARGE (MSG-398 D6 —
	// 0.5도에서 한 종류 최대 169건 65KB, 4도만 돼도 전국의 92%가 실린다. 격자 개별 조회와 같은 값·같은 전환점).
	private static final double MAX_VIEWPORT_SPAN_DEG = 0.5;

	// WGS84 좌표 유효 범위 — 서비스 범위(KoreaCoordinates)가 아니라 좌표계 자체의 정의역이다(D3 기준선).
	// 한국 밖이지만 정의역 안인 bbox 는 오류가 아니라 빈 배열 200 이다(D6).
	private static final double MIN_LATITUDE_DEG = -90.0;
	private static final double MAX_LATITUDE_DEG = 90.0;
	private static final double MIN_LONGITUDE_DEG = -180.0;
	private static final double MAX_LONGITUDE_DEG = 180.0;

	/** seq 오름차순, seq NULL 스팟은 뒤로, 동일 seq 는 gridId 로 결정적 정렬(§도메인 3). */
	private static final Comparator<MissionGrid> SPOT_ORDER =
		Comparator.comparing(MissionGrid::getSeq, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(MissionGrid::getGridId);

	private final MissionRepository missionRepository;
	private final MissionGridRepository missionGridRepository;
	private final VideoRepository videoRepository;
	private final ObjectMapper objectMapper;
	private final MissionViewportProperties viewportProperties;
	private final RegionQueryService regionQueryService;
	private final Clock clock;
	private final long ttlMillis;

	/** 재계산 직렬화 전용 락 — this 동기화 회피(외부 synchronized 간섭 차단). */
	private final Object refreshLock = new Object();

	/** 스냅샷+만료시각을 단일 volatile 로 원자 발행 — 두 값 찢어진 읽기(옛 데이터+새 만료)를 차단. */
	private volatile CacheEntry cache;

	@Autowired
	public MissionQueryServiceImpl(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		VideoRepository videoRepository,
		ObjectMapper objectMapper,
		MissionViewportProperties viewportProperties,
		RegionQueryService regionQueryService
	) {
		// systemUTC: findActive 의 :now 는 UTC 저장 start_at/end_at 과 비교된다 — 기본존(로컬 KST)이면 +9h 스큐.
		this(missionRepository, missionGridRepository, videoRepository, objectMapper, viewportProperties,
			regionQueryService, Clock.systemUTC(), CACHE_TTL.toMillis());
	}

	/** 캐시 만료 검증용 — 클럭·TTL 을 주입한다(§테스트 시나리오 캐시). */
	public MissionQueryServiceImpl(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		VideoRepository videoRepository,
		ObjectMapper objectMapper,
		MissionViewportProperties viewportProperties,
		RegionQueryService regionQueryService,
		Clock clock,
		long ttlMillis
	) {
		this.missionRepository = missionRepository;
		this.missionGridRepository = missionGridRepository;
		this.videoRepository = videoRepository;
		this.objectMapper = objectMapper;
		this.viewportProperties = viewportProperties;
		this.regionQueryService = regionQueryService;
		this.clock = clock;
		this.ttlMillis = ttlMillis;
	}

	/**
	 * 부트 웜업 (MSG-437 후속) — 기동 직후 스냅숏을 한 번 미리 계산해, 첫 사용자 요청이 콜드 재계산 비용을
	 * 물지 않게 한다. dev 실측에서 콜드 첫 요청이 1.51초(웜 12ms)였고 그 차이는 활성 축제·팝업 전량의
	 * 역지오코딩이다. 재시작당 한 번뿐인 비용이라 사용자가 없는 기동 시점으로 옮기는 것이 최소 변경이다
	 * (D1 이 예고한 판단 지점의 결론 — unnest 배치 승격은 활성 미션이 수천 건이 될 때로 유지).
	 *
	 * ApplicationReadyEvent 는 시더(ApplicationRunner)들이 끝난 뒤 발화하므로, 시딩을 켠 기동에서도
	 * 방금 적재한 미션이 그대로 스냅숏에 들어온다.
	 *
	 * 실패해도 기동을 죽이지 않는다 — 웜업은 성능 최적화지 기동 조건이 아니다. 재계산이 실패하면 스냅숏이
	 * 발행되지 않은 상태 그대로라 첫 사용자 요청이 재계산을 다시 시도한다(D1 의미론 그대로).
	 *
	 * NOT_SUPPORTED 로 클래스 레벨 {@code @Transactional(readOnly = true)} 밖에 둔다. 그대로 두면 프록시가
	 * 리스너 진입 시점에 트랜잭션을 열어야 하는데, 하필 이 목표가 겨냥한 기동 시 DB 장애에서는 그 열기가
	 * CannotCreateTransactionException 으로 실패한다 — 아래 try 에 닿기도 전이라 예외가 리스너 밖으로 나가
	 * 기동이 죽는다. 트랜잭션 밖이면 프록시가 DB 를 건드리지 않아 그 경로 자체가 사라지고, snapshot() 안의
	 * 리포지토리 호출들은 Spring Data 자체 트랜잭션으로 돈다(재계산은 원래 호출자 트랜잭션에 기대지 않는
	 * 읽기 조합이라 동작이 같다). 이 어노테이션을 지우면 결함이 조용히 되살아난다 (Codex P1, 리뷰 반영).
	 */
	@EventListener(ApplicationReadyEvent.class)
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void warmUpSnapshot() {
		try {
			log.info("미션 스냅숏 부트 웜업 완료: 활성 미션 {}건 (MSG-437)", snapshot().size());
		} catch (RuntimeException e) {
			log.warn("미션 스냅숏 부트 웜업에 실패해 첫 조회 요청이 재계산합니다 (MSG-437)", e);
		}
	}

	@Override
	public List<MissionResponseDto> getMissionsInViewport(ViewportBounds bounds, MissionType type) {
		validateBounds(bounds, MAX_VIEWPORT_SPAN_DEG);
		List<CachedMission> snapshot = snapshot();
		// 스냅샷은 불변 리스트 — 필터는 새 리스트를 만들고 캐시에 담긴 리스트를 변형하지 않는다.
		GridRange view = GridEncoder.viewportRange(bounds);
		long pad = PROJECTION_PAD_CELLS + viewportProperties.marginCells(type);
		return snapshot.stream()
			.filter(cached -> cached.type() == type)
			.filter(cached -> intersects(cached.bounds(), view, pad))
			.map(CachedMission::dto)
			.toList();
	}

	/**
	 * 행정 단위 집계 (MSG-437 D2) — 목록과 같은 스냅숏 위 산술이라 요청 경로에서 DB 를 타지 않는다.
	 * type 필터 → 귀속점이 bbox 안(경계 포함, D3)인 미션 선별 → 행정동 코드 접두로 그룹핑 → 항목 조립.
	 * 무귀속 미션은 제외가 아니라 키 null 인 한 그룹으로 모여 맨 뒤에 실린다(MSG-356 NULLS LAST 와 같다).
	 */
	@Override
	public List<MissionRegionAggregateResponseDto> getMissionAggregates(
		ViewportBounds bounds, MissionType type, RegionUnit unit) {
		validateBounds(bounds, unit.getMaxSpanDeg());
		// HashMap 은 null 키를 받는다 — Collectors.groupingBy 는 무귀속 키에서 NPE 라 직접 모은다.
		Map<String, List<CachedMission>> grouped = new HashMap<>();
		for (CachedMission cached : snapshot()) {
			if (cached.type() != type || !containsAnchor(bounds, cached.anchor())) {
				continue;
			}
			grouped.computeIfAbsent(regionKey(cached.anchor(), unit), key -> new ArrayList<>()).add(cached);
		}
		return grouped.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.nullsLast(Comparator.naturalOrder())))
			.map(entry -> toAggregate(entry.getKey(), entry.getValue(), unit))
			.toList();
	}

	/** 귀속점이 bbox 안인지 — 경계선 위는 포함이다(D3). 귀속점이 없는 미션(격자 없음·비집계 유형)은 제외. */
	private static boolean containsAnchor(ViewportBounds bounds, RegionAnchor anchor) {
		return anchor != null
			&& anchor.lat() >= bounds.swLat() && anchor.lat() <= bounds.neLat()
			&& anchor.lon() >= bounds.swLng() && anchor.lon() <= bounds.neLng();
	}

	/** 묶음 키 — 행정동 코드를 단위 길이로 자른 접두. 무귀속은 null 키 한 그룹으로 모인다. */
	private static String regionKey(RegionAnchor anchor, RegionUnit unit) {
		String regionCode = anchor.regionCode();
		if (regionCode == null) {
			return null;
		}
		return regionCode.substring(0, Math.min(unit.getCodePrefixLength(), regionCode.length()));
	}

	private static MissionRegionAggregateResponseDto toAggregate(
		String regionCode, List<CachedMission> group, RegionUnit unit) {
		// MIN 단일화 — 같은 접두면 이름도 한 값이지만 결정적으로 하나를 고른다(MSG-356 MIN(split_part) 와 동일).
		String name = group.stream()
			.map(cached -> nameToken(cached.anchor().regionFullName(), unit.getNameTokenIndex()))
			.filter(Objects::nonNull)
			.min(Comparator.naturalOrder())
			.orElse(null);
		double lat = group.stream().mapToDouble(cached -> cached.anchor().lat()).average().orElseThrow();
		double lng = group.stream().mapToDouble(cached -> cached.anchor().lon()).average().orElseThrow();
		List<Long> missionIds = group.stream().map(cached -> cached.dto().missionId()).sorted().toList();
		return new MissionRegionAggregateResponseDto(regionCode, name, lat, lng, group.size(), missionIds);
	}

	/**
	 * 전체 경로 이름("부산광역시 부산진구 부전2동")의 tokenIndex 번째 공백 토큰 — 도감 집계의
	 * split_part(region_name, ' ', n) 와 동작을 맞춘다(MSG-356). 토큰이 모자라면 split_part 처럼 빈 문자열이고,
	 * 이름 자체가 없으면(무귀속) null 이다. 같은 화면의 도감 마커와 지역 이름이 갈리지 않는 것이 우선이다(D2).
	 */
	private static String nameToken(String fullName, int tokenIndex) {
		if (fullName == null) {
			return null;
		}
		String[] tokens = fullName.split(" ", -1);
		return tokenIndex <= tokens.length ? tokens[tokenIndex - 1] : "";
	}

	@Override
	public List<MissionProgressResponseDto> getMyProgress(long userId, List<Long> missionIds) {
		// 0개를 물으면 0개가 오는 것이 벌크 조회의 자연스러운 답이다(D7) — 오류가 아니다.
		if (missionIds == null || missionIds.isEmpty()) {
			return List.of();
		}
		if (missionIds.size() > MAX_PROGRESS_IDS) {
			throw new ApiException(MissionErrorCode.TOO_MANY_MISSION_IDS);
		}
		return missionRepository.findProgress(userId, missionIds).stream()
			.map(MissionQueryServiceImpl::toProgressDto)
			.toList();
	}

	/**
	 * 미션 상세 조립 (MSG-399 §도메인 로직). DB 조회는 최대 다섯 번 고정이고 미션 격자 수가 늘어도
	 * 횟수가 늘지 않는다 — 스팟마다 DB 를 다시 읽지 않는다. 진행도는 findProgress 재사용(같은 교집합
	 * 계산을 상세용 SQL 로 복제하지 않는다), 방문 여부는 진행도와 같은 술어(findVisitedGridIds),
	 * 영상 개수는 MSG-390 후보 술어의 격자별 COUNT 합이다. 사용자별 값이라 전역 캐시를 타지 않는다.
	 *
	 * <p>userId 가 null 이면 비로그인 조회다(MSG-454) — 사용자별 두 조회를 아예 건너뛰어(조회 세 번)
	 * progress 는 null, 방문 격자는 빈 집합이 되고 spotStats.visited 가 전부 false 로 조립된다.
	 */
	// REPEATABLE_READ: 같은 술어를 약속하는 progress 와 spotStats.visited 가 한 응답에서 갈라지지 않게
	// 다섯 읽기를 한 스냅숏으로 묶는다 — 읽기 전용이라 직렬화 실패 부작용 없음 (Codex P2).
	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MissionDetailResponseDto getMissionDetail(long missionId, Long userId) {
		// 기간 판정은 하지 않는다 — 기간이 끝난 미션도 행이 남아 있으면 상세를 연다(§API 명세).
		// 노출 중지된 미션은 없는 미션과 <b>같은 404</b> 다 (MSG-500 D-3) — 목록에서 사라진 미션이 상세로는
		// 열리면 "있는데 안 보이는" 상태가 되어 은닉이 갈라진다.
		Mission mission = missionRepository.findById(missionId)
			.filter(found -> found.getHiddenAt() == null)
			.orElseThrow(() -> new ApiException(MissionErrorCode.MISSION_NOT_FOUND));
		List<MissionGrid> grids = missionGridRepository.findByMissionIds(List.of(missionId));
		// findById 직후라 진행도 행은 사실상 하나다. READ_COMMITTED 는 문장 사이 스냅샷이 갱신되므로
		// 그 사이 시더 정리가 커밋되는 극소 경합(부팅 시·스탬프 없는 종료 미션 한정)은 감수한다.
		MissionProgressResponseDto progress = userId == null ? null
			: toProgressDto(missionRepository.findProgress(userId, List.of(missionId)).get(0));
		Set<String> visitedGridIds = userId == null ? Set.of()
			: Set.copyOf(missionGridRepository.findVisitedGridIds(missionId, userId));
		Map<String, Long> videoCountByGrid = videoRepository.countMissionVideosByGrid(missionId).stream()
			.collect(Collectors.toMap(
				MissionVideoCountProjection::getGridId, MissionVideoCountProjection::getVideoCount));
		long videoCount = videoCountByGrid.values().stream().mapToLong(Long::longValue).sum();

		MissionResponseDto missionDto = MissionResponseDto.of(mission, buildShape(mission, grids));
		return new MissionDetailResponseDto(missionDto, progress, videoCount,
			spotStats(mission, grids, visitedGridIds, videoCountByGrid));
	}

	/**
	 * 격자 역조회 (MSG-459 §API 2). 대표 격자 컬럼 하나로 찾고(D-4), 영상 수는 미션 id 목록으로 한 번에
	 * 센다 — 항목마다 세면 N+1 이다. 캐시를 읽지 않는 것은 방금 시작하거나 방금 끝난 미션이 누락되면 안
	 * 되기 때문이고, awardOnUpload 가 같은 이유로 캐시를 우회하는 것과 같다.
	 */
	@Override
	public List<GridMissionResponseDto> getMissionsByGrid(String gridId) {
		List<Mission> missions = missionRepository.findByRepresentativeGridIdAndHiddenAtIsNullOrderById(gridId);
		if (missions.isEmpty()) {
			return List.of();
		}
		Map<Long, Long> videoCounts = videoRepository
			.countVideosByMissionIds(missions.stream().map(Mission::getId).toList()).stream()
			.collect(Collectors.toMap(
				MissionTotalVideoCountProjection::getMissionId, MissionTotalVideoCountProjection::getVideoCount));
		LocalDateTime now = LocalDateTime.now(clock);
		return missions.stream()
			.sorted(reverseLookupOrder(now))
			.map(mission -> new GridMissionResponseDto(
				mission.getId(),
				mission.getType().name(),
				mission.getTitle(),
				mission.getStartAt(),
				mission.getEndAt(),
				videoCounts.getOrDefault(mission.getId(), 0L)))
			.toList();
	}

	/**
	 * 역조회 표시 순서 (§API 2) — 진행 중 → 시작 전(임박한 순) → 종료(최근 종료 순). 배열 첫 항목이
	 * 화면 진입 기본값이 되므로 이 순서가 계약이다. 무리 안에서 시각이 같으면 id 로 갈라 결정적이다.
	 */
	private Comparator<Mission> reverseLookupOrder(LocalDateTime now) {
		return Comparator.comparingInt((Mission mission) -> phase(mission, now))
			.thenComparingLong(mission -> phaseKey(mission, now))
			.thenComparing(Mission::getId);
	}

	/**
	 * 0 진행 중 · 1 시작 전 · 2 종료. startAt·endAt 이 둘 다 없으면 무기간이며(시더는 축제·팝업에 기간을
	 * 항상 채우므로 수동 삽입 한정) 진행 중으로 본다 — 업로드 경로의 within() 이 NULL 을 상시 활성으로
	 * 읽는 것과 같은 해석이다. chk_missions_rep_grid_type 은 유형과 목표 칸수만 걸고 기간은 보지 않으니
	 * 그런 행이 여기 올 수 있고, 아래 null 검사가 그래서 필요하다.
	 */
	private int phase(Mission mission, LocalDateTime now) {
		if (mission.getStartAt() != null && mission.getStartAt().isAfter(now)) {
			return 1;
		}
		if (mission.getEndAt() != null && mission.getEndAt().isBefore(now)) {
			return 2;
		}
		return 0;
	}

	/**
	 * 무리 안 정렬 키 — 시작 전은 시작이 이른 것이 먼저(임박한 순)고, 종료는 늦게 끝난 것이 먼저라
	 * 부호를 뒤집는다. 진행 중은 키가 없어 id 순으로만 갈린다.
	 */
	private long phaseKey(Mission mission, LocalDateTime now) {
		return switch (phase(mission, now)) {
			case 1 -> mission.getStartAt().toEpochSecond(ZoneOffset.UTC);
			case 2 -> -mission.getEndAt().toEpochSecond(ZoneOffset.UTC);
			default -> 0L;
		};
	}

	/**
	 * COURSE 만 스팟별 통계를 조립한다 — 정렬은 shape.spots 와 같은 SPOT_ORDER(seq ASC NULLS LAST,
	 * gridId ASC)라 두 배열이 항목 단위로 대응한다. 영상이 없는 스팟은 COUNT 결과에 행이 없으므로 0 으로
	 * 채워 빠뜨리지 않는다. 코스가 아니면 null 대신 빈 배열이다(§응답 DTO).
	 */
	private List<SpotStats> spotStats(Mission mission, List<MissionGrid> grids,
		Set<String> visitedGridIds, Map<String, Long> videoCountByGrid) {
		if (mission.getType() != MissionType.COURSE) {
			return List.of();
		}
		return grids.stream()
			.sorted(SPOT_ORDER)
			.map(grid -> new SpotStats(grid.getGridId(), visitedGridIds.contains(grid.getGridId()),
				videoCountByGrid.getOrDefault(grid.getGridId(), 0L)))
			.toList();
	}

	private static MissionProgressResponseDto toProgressDto(MissionProgressProjection row) {
		return new MissionProgressResponseDto(
			row.getMissionId(), row.getTargetCount(), row.getFilledCount(), Boolean.TRUE.equals(row.getCompleted()));
	}

	/**
	 * bbox 검증 (MSG-398 D6) — 좌표 유효성 → 뒤집힘 → span 상한 순서. 좌표 자체 검증이 맨 앞이다:
	 * NaN 은 어떤 비교도 false 라 뒤집힘·상한 검사를 그대로 통과하고(fail-open), 1e308 급 유한값은
	 * viewportRange 가 쓰는 Proj4J 경도 정규화(반복 감산)를 사실상 무한 루프로 만든다.
	 * ponytail: GridQueryServiceImpl.validateBounds 의 쌍둥이(여덟 줄 복제) — Owner A 코드를 고치지 않으려는
	 * 의도적 복제다. 세 번째 호출자가 생기면 global/geo 로 승격한다 (MSG-398 D6).
	 *
	 * maxSpanDeg 는 호출 경로마다 다르다 — 개별 목록은 0.5도 고정이고, 행정 집계는 단위별 상한
	 * (RegionUnit.maxSpanDeg — DONG 1도·SIGUNGU 4도·SIDO 10도)이다 (MSG-437). 정확히 상한값은 허용한다.
	 */
	private void validateBounds(ViewportBounds bounds, double maxSpanDeg) {
		if (!isValidCoordinates(bounds)) {
			throw new ApiException(MissionErrorCode.INVALID_VIEWPORT);
		}
		if (bounds.swLat() > bounds.neLat() || bounds.swLng() > bounds.neLng()) {
			throw new ApiException(MissionErrorCode.INVALID_VIEWPORT);
		}
		double latSpan = bounds.neLat() - bounds.swLat();
		double lngSpan = bounds.neLng() - bounds.swLng();
		if (latSpan > maxSpanDeg || lngSpan > maxSpanDeg) {
			throw new ApiException(MissionErrorCode.VIEWPORT_TOO_LARGE);
		}
	}

	/** 네 좌표가 모두 WGS84 유효 범위 안인지 — 범위 비교가 NaN(두 비교 모두 false)·±무한대까지 함께 걸러낸다. */
	private boolean isValidCoordinates(ViewportBounds bounds) {
		return isValidLat(bounds.swLat()) && isValidLat(bounds.neLat())
			&& isValidLng(bounds.swLng()) && isValidLng(bounds.neLng());
	}

	private boolean isValidLat(double lat) {
		return lat >= MIN_LATITUDE_DEG && lat <= MAX_LATITUDE_DEG;
	}

	private boolean isValidLng(double lng) {
		return lng >= MIN_LONGITUDE_DEG && lng <= MAX_LONGITUDE_DEG;
	}

	/**
	 * bbox 교차 판정 (D2) — 정수 사각형 넷 비교라 과다 포함 쪽으로만 틀리고 누락이 없다.
	 * 사각형이 없는 미션(격자도 쓸 수 있는 경로도 없음)은 위치를 알 수 없어 어느 뷰포트에도 실리지 않는다.
	 */
	private boolean intersects(GridRange mission, GridRange view, long pad) {
		if (mission == null) {
			return false;
		}
		return mission.maxGridY() >= view.minGridY() - pad && mission.minGridY() <= view.maxGridY() + pad
			&& mission.maxGridX() >= view.minGridX() - pad && mission.minGridX() <= view.maxGridX() + pad;
	}

	/**
	 * 스냅숏 무효화 (MSG-500 D-12) — 홀더를 비워 다음 조회가 재계산하게 한다. 재계산 락 안에서 비우는 것은
	 * 이미 진행 중인 재계산이 방금 비운 홀더를 옛 결과로 도로 채우는 순서를 막기 위해서다. 그래도 남는
	 * 경합이 하나 있다: 무효화 직전에 락을 잡아 재계산을 마친 스레드의 결과는 지워진다(그 재계산은 무효화를
	 * 부른 변경을 못 봤을 수 있다). 호출부가 커밋 <b>후에</b> 부르므로 그 창은 커밋 시점과 이 호출 사이로
	 * 좁고, 최악의 결과는 다음 조회 한 번이 옛 스냅숏을 보는 것이다(TTL 1시간 지연과 비교하면 미미하다).
	 */
	@Override
	public void invalidateSnapshot() {
		synchronized (refreshLock) {
			this.cache = null;
		}
	}

	private List<CachedMission> snapshot() {
		CacheEntry current = cache;
		if (current != null && clock.millis() < current.expiresAtMillis()) {
			return current.snapshot();
		}
		synchronized (refreshLock) {
			// 재확인 — 대기 중 다른 스레드가 방금 갱신했으면 그 엔트리를 재사용(중복 조회·세대 역전 차단).
			CacheEntry latest = cache;
			if (latest != null && clock.millis() < latest.expiresAtMillis()) {
				return latest.snapshot();
			}
			// 재계산이 실패하면 cache 를 갱신하지 않는다 — 뭉개진 세대가 발행되지 않고 다음 요청이 다시 시도한다(D1).
			List<CachedMission> recomputed = recompute(latest);
			this.cache = new CacheEntry(recomputed, clock.millis() + ttlMillis);
			return recomputed;
		}
	}

	/** 스냅샷+만료시각 원자 발행 단위 — 단일 volatile 참조 1회 로드로 두 값을 함께 읽어 세대 원자성을 보장한다. */
	private record CacheEntry(List<CachedMission> snapshot, long expiresAtMillis) {
	}

	/**
	 * 스냅샷 한 줄 — 응답 DTO 와 뷰포트 판정용 정수 사각형(MSG-398 D2), 행정 집계용 귀속(MSG-437 D1)을
	 * 함께 들고 다닌다. anchor 는 집계 대상 유형(EVENT·POPUP)이면서 판정 사각형이 있는 미션에만 있다.
	 */
	private record CachedMission(MissionResponseDto dto, MissionType type, GridRange bounds, RegionAnchor anchor) {
	}

	/**
	 * 미션의 행정 귀속 (MSG-437 D1) — 판정 사각형 중앙 셀 중심의 위경도와 그 점이 속한 행정동이다.
	 * 포함 행정동이 없거나 서비스 범위 밖이면 좌표만 남고 regionCode·regionFullName 이 null 이다(무귀속).
	 */
	private record RegionAnchor(double lat, double lon, String regionCode, String regionFullName) {
	}

	/**
	 * active 미션 → mission_grids 일괄 조회(2쿼리) → missionId 그룹핑 → 유형별 shape·사각형 합성(§도메인 1)
	 * → 집계 대상 유형의 행정 귀속 판정(MSG-437 D1). previous 는 직전 세대로, 판정 사각형이 그대로인
	 * 미션의 귀속을 다시 묻지 않고 옮겨 싣는 메모이즈 재료다.
	 */
	private List<CachedMission> recompute(CacheEntry previous) {
		List<Mission> missions = missionRepository.findActive(LocalDateTime.now(clock));
		if (missions.isEmpty()) {
			return List.of();
		}
		List<Long> missionIds = missions.stream().map(Mission::getId).toList();
		Map<Long, List<MissionGrid>> gridsByMission = missionGridRepository.findByMissionIds(missionIds).stream()
			.collect(Collectors.groupingBy(MissionGrid::getMissionId));
		Map<Long, CachedMission> memo = previous == null ? Map.of() : previous.snapshot().stream()
			.collect(Collectors.toMap(cached -> cached.dto().missionId(), cached -> cached, (a, b) -> a));

		return missions.stream()
			.map(mission -> {
				List<MissionGrid> grids = gridsByMission.getOrDefault(mission.getId(), List.of());
				GridRange bounds = missionBounds(mission, grids);
				return new CachedMission(
					MissionResponseDto.of(mission, buildShape(mission, grids)),
					mission.getType(),
					bounds,
					resolveAnchor(mission, bounds, memo));
			})
			.toList();
	}

	/**
	 * 행정 귀속 판정 (MSG-437 D1). 집계 대상은 EVENT·POPUP 뿐이라 나머지 유형은 판정을 부르지 않고,
	 * 격자가 하나도 없어 사각형이 없는 미션도 위치를 모르므로 건너뛴다. 직전 세대의 판정 사각형이 같으면
	 * 역지오코딩을 다시 부르지 않는다 — 미션은 수동 시딩이라 정상 상태의 추가 쿼리가 신규·변경 건수로 떨어진다.
	 *
	 * 무귀속으로 수용하는 실패는 둘뿐이다: 포함 행정동 없음(Optional.empty)과 서비스 범위 밖 좌표(6400).
	 * 둘 다 데이터 성질이라 다시 물어도 같으므로 그 미션만 코드 없이 싣고 warn 을 남긴다. 그 밖의 예외
	 * (DB 장애 등)는 흡수하지 않고 전파한다 — 일괄 흡수하면 장애 한 번에 전 미션이 무귀속으로 뭉개진
	 * 스냅숏이 1시간 캐시되는 사고가 난다.
	 */
	private RegionAnchor resolveAnchor(Mission mission, GridRange bounds, Map<Long, CachedMission> memo) {
		if (bounds == null || !AGGREGATABLE_TYPES.contains(mission.getType())) {
			return null;
		}
		CachedMission previous = memo.get(mission.getId());
		if (previous != null && previous.anchor() != null && bounds.equals(previous.bounds())) {
			return previous.anchor();
		}
		GridPoint anchorPoint = GridEncoder.center(
			(bounds.minGridY() + bounds.maxGridY()) / 2 + "_" + (bounds.minGridX() + bounds.maxGridX()) / 2);
		try {
			return regionQueryService.resolveByPoint(anchorPoint.lat(), anchorPoint.lon())
				.map(region -> new RegionAnchor(
					anchorPoint.lat(), anchorPoint.lon(), region.regionCode(), region.regionName()))
				.orElseGet(() -> {
					log.warn("미션 귀속점을 포함하는 행정동이 없어 무귀속으로 집계합니다 (MSG-437 D1): "
						+ "missionId={} lat={} lon={}", mission.getId(), anchorPoint.lat(), anchorPoint.lon());
					return new RegionAnchor(anchorPoint.lat(), anchorPoint.lon(), null, null);
				});
		} catch (ApiException e) {
			if (e.getErrorCode() != RegionErrorCode.INVALID_COORDINATE) {
				throw e;
			}
			log.warn("미션 귀속점이 서비스 범위 밖이라 무귀속으로 집계합니다 (MSG-437 D1): missionId={} lat={} lon={}",
				mission.getId(), anchorPoint.lat(), anchorPoint.lon());
			return new RegionAnchor(anchorPoint.lat(), anchorPoint.lon(), null, null);
		}
	}

	/**
	 * 뷰포트 판정용 정수 사각형 (MSG-398 D3). 코스는 화면에 그려지는 것이 path 폴리라인 전체라 경로 점마다
	 * 환산한 min/max 를 쓰고(스팟 사각형은 경로 양 끝을 흘린다), 그 밖의 유형은 mission_grids 가 곧 판정
	 * 범위이자 표시 범위라 격자 인덱스의 min/max 다. 코스 경로를 못 쓰면(NULL·파싱 불가·범위 밖 좌표)
	 * 스팟 사각형으로 내려간다 — 같은 폴백 한 자리로 합류한다.
	 */
	private GridRange missionBounds(Mission mission, List<MissionGrid> grids) {
		if (mission.getType() == MissionType.COURSE) {
			GridRange pathBounds = pathBounds(mission);
			if (pathBounds != null) {
				return pathBounds;
			}
		}
		return gridBounds(grids);
	}

	/**
	 * path LineString 의 좌표를 점마다 격자 인덱스로 환산해 min/max 를 직접 잡는다 — 위경도 사각형을
	 * 중간에 만들지 않는다. EPSG:5179 는 횡축 메르카토르라 위경도 사각형의 네 귀퉁이 투영점으로 만든
	 * 사각형이 변 위의 점을 밖으로 흘릴 수 있어(코스 실측 0.4m 이탈), 경로가 셀 경계 가까이 지나가면
	 * 한 칸이 통째로 빠진다(D3).
	 *
	 * 좌표는 변환 전에 서비스 범위로 거른다 — 1e308 같은 유한한 쓰레기 값이 Proj4J 경도 정규화를 사실상
	 * 무한 루프로 만들고, 이 재계산은 refreshLock 안이라 멈추면 그 뒤의 모든 조회가 함께 멈춘다.
	 * 범위 밖 점을 만나면 예외를 던지지 않고 그 코스만 폴백(null 반환)으로 내려간다 — 성한 점만 골라
	 * 계속 계산하면 경로 한쪽이 빠진 사각형이 정상처럼 나가 조용히 틀린다(D3 기각안).
	 */
	private GridRange pathBounds(Mission mission) {
		if (mission.getPath() == null) {
			return null;
		}
		try {
			JsonNode coordinates = objectMapper.readTree(mission.getPath()).path("coordinates");
			if (!coordinates.isArray() || coordinates.isEmpty()) {
				return null;
			}
			long minY = Long.MAX_VALUE;
			long maxY = Long.MIN_VALUE;
			long minX = Long.MAX_VALUE;
			long maxX = Long.MIN_VALUE;
			for (JsonNode point : coordinates) {
				JsonNode lonNode = point.path(0);
				JsonNode latNode = point.path(1);
				if (!lonNode.isNumber() || !latNode.isNumber()) {
					log.warn("코스 path 좌표가 [lon, lat] 숫자 쌍이 아니라 스팟 사각형으로 폴백합니다 (MSG-398 D3): "
						+ "missionId={} point={}", mission.getId(), point);
					return null;
				}
				double lon = lonNode.asDouble();
				double lat = latNode.asDouble();
				if (KoreaCoordinates.isOutOfService(lat, lon)) {
					log.warn("코스 path 좌표가 서비스 범위 밖이라 스팟 사각형으로 폴백합니다 (MSG-398 D3): "
						+ "missionId={} lat={} lon={}", mission.getId(), lat, lon);
					return null;
				}
				GridIndex index = GridEncoder.decode(GridEncoder.encode(lat, lon));
				minY = Math.min(minY, index.gridY());
				maxY = Math.max(maxY, index.gridY());
				minX = Math.min(minX, index.gridX());
				maxX = Math.max(maxX, index.gridX());
			}
			return new GridRange(minY, maxY, minX, maxX);
		} catch (RuntimeException e) {
			// Jackson 3 파싱 예외는 전부 unchecked — 어떤 경우에도 재계산 전체를 세우지 않는다(D3).
			log.warn("코스 path 파싱에 실패해 스팟 사각형으로 폴백합니다 (MSG-398 D3): missionId={}",
				mission.getId(), e);
			return null;
		}
	}

	/** mission_grids 인덱스의 min/max — 격자가 하나도 없으면 사각형이 없다(null). */
	private GridRange gridBounds(List<MissionGrid> grids) {
		if (grids.isEmpty()) {
			return null;
		}
		long minY = Long.MAX_VALUE;
		long maxY = Long.MIN_VALUE;
		long minX = Long.MAX_VALUE;
		long maxX = Long.MIN_VALUE;
		for (MissionGrid grid : grids) {
			GridIndex index = GridEncoder.decode(grid.getGridId());
			minY = Math.min(minY, index.gridY());
			maxY = Math.max(maxY, index.gridY());
			minX = Math.min(minX, index.gridX());
			maxX = Math.max(maxX, index.gridX());
		}
		return new GridRange(minY, maxY, minX, maxX);
	}

	/** 유형 → shape 단일 분기(전략 클래스 없음, §도메인 3). */
	private MissionShape buildShape(Mission mission, List<MissionGrid> grids) {
		return switch (mission.getType()) {
			case COURSE -> pathShape(mission, grids);
			case AREA -> new RegionShape(mission.getRegionCode());
			case EVENT, POPUP -> boxShape(grids);
			case THEME, CONTINUOUS -> cellsShape(grids);
		};
	}

	/** PATH — line = path 원문 passthrough, spots = mission_grids seq 오름차순 중심점(§도메인 3). */
	private PathShape pathShape(Mission mission, List<MissionGrid> grids) {
		List<Spot> spots = grids.stream()
			.sorted(SPOT_ORDER)
			.map(grid -> {
				GridPoint center = GridEncoder.center(grid.getGridId());
				// 이름은 저장 컬럼 통과다 — 여기서 계산하지 않는다(MSG-492 D-2). 조회 경로에 쿼리도 의존도 안 는다.
				return new Spot(grid.getGridId(), center.lat(), center.lon(), grid.getSeq(), grid.getName());
			})
			.toList();
		return new PathShape(mission.getPath(), spots);
	}

	/**
	 * BOX — mission_grids 각 셀 bbox 코너를 모아 전역 min/max lat·lon 을 낸 뒤 5점 닫힌 링을 만든다
	 * (남서→남동→북동→북서→남서, §도메인 3). 격자 집합이 비면 방어적으로 빈 폴리곤.
	 */
	private BoxShape boxShape(List<MissionGrid> grids) {
		if (grids.isEmpty()) {
			return new BoxShape(List.of());
		}
		double minLat = Double.POSITIVE_INFINITY;
		double minLon = Double.POSITIVE_INFINITY;
		double maxLat = Double.NEGATIVE_INFINITY;
		double maxLon = Double.NEGATIVE_INFINITY;
		for (MissionGrid grid : grids) {
			for (GridPoint corner : GridEncoder.bbox(grid.getGridId())) {
				minLat = Math.min(minLat, corner.lat());
				minLon = Math.min(minLon, corner.lon());
				maxLat = Math.max(maxLat, corner.lat());
				maxLon = Math.max(maxLon, corner.lon());
			}
		}
		List<LatLng> polygon = List.of(
			new LatLng(minLat, minLon),
			new LatLng(minLat, maxLon),
			new LatLng(maxLat, maxLon),
			new LatLng(maxLat, minLon),
			new LatLng(minLat, minLon)
		);
		return new BoxShape(polygon);
	}

	/** CELLS — 각 mission_grids 격자 중심점(seq 무의미, §도메인 3). */
	private CellsShape cellsShape(List<MissionGrid> grids) {
		List<Cell> cells = grids.stream()
			.map(grid -> {
				GridPoint center = GridEncoder.center(grid.getGridId());
				return new Cell(grid.getGridId(), center.lat(), center.lon());
			})
			.toList();
		return new CellsShape(cells);
	}
}
