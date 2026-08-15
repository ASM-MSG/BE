package com.msg.fillmap.mission.service.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
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
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.dto.MissionProgressResponseDto;
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
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.MissionQueryService;

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
	private final ObjectMapper objectMapper;
	private final MissionViewportProperties viewportProperties;
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
		ObjectMapper objectMapper,
		MissionViewportProperties viewportProperties
	) {
		// systemUTC: findActive 의 :now 는 UTC 저장 start_at/end_at 과 비교된다 — 기본존(로컬 KST)이면 +9h 스큐.
		this(missionRepository, missionGridRepository, objectMapper, viewportProperties,
			Clock.systemUTC(), CACHE_TTL.toMillis());
	}

	/** 캐시 만료 검증용 — 클럭·TTL 을 주입한다(§테스트 시나리오 캐시). */
	public MissionQueryServiceImpl(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		ObjectMapper objectMapper,
		MissionViewportProperties viewportProperties,
		Clock clock,
		long ttlMillis
	) {
		this.missionRepository = missionRepository;
		this.missionGridRepository = missionGridRepository;
		this.objectMapper = objectMapper;
		this.viewportProperties = viewportProperties;
		this.clock = clock;
		this.ttlMillis = ttlMillis;
	}

	@Override
	public List<MissionResponseDto> getMissionsInViewport(ViewportBounds bounds, MissionType type) {
		validateBounds(bounds);
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
			.map(row -> new MissionProgressResponseDto(
				row.getMissionId(), row.getTargetCount(), row.getFilledCount(), Boolean.TRUE.equals(row.getCompleted())))
			.toList();
	}

	/**
	 * bbox 검증 (MSG-398 D6) — 좌표 유효성 → 뒤집힘 → span 상한 순서. 좌표 자체 검증이 맨 앞이다:
	 * NaN 은 어떤 비교도 false 라 뒤집힘·상한 검사를 그대로 통과하고(fail-open), 1e308 급 유한값은
	 * viewportRange 가 쓰는 Proj4J 경도 정규화(반복 감산)를 사실상 무한 루프로 만든다.
	 * ponytail: GridQueryServiceImpl.validateBounds 의 쌍둥이(여덟 줄 복제) — Owner A 코드를 고치지 않으려는
	 * 의도적 복제다. 세 번째 호출자가 생기면 global/geo 로 승격한다 (MSG-398 D6).
	 */
	private void validateBounds(ViewportBounds bounds) {
		if (!isValidCoordinates(bounds)) {
			throw new ApiException(MissionErrorCode.INVALID_VIEWPORT);
		}
		if (bounds.swLat() > bounds.neLat() || bounds.swLng() > bounds.neLng()) {
			throw new ApiException(MissionErrorCode.INVALID_VIEWPORT);
		}
		double latSpan = bounds.neLat() - bounds.swLat();
		double lngSpan = bounds.neLng() - bounds.swLng();
		if (latSpan > MAX_VIEWPORT_SPAN_DEG || lngSpan > MAX_VIEWPORT_SPAN_DEG) {
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
			List<CachedMission> recomputed = recompute();
			this.cache = new CacheEntry(recomputed, clock.millis() + ttlMillis);
			return recomputed;
		}
	}

	/** 스냅샷+만료시각 원자 발행 단위 — 단일 volatile 참조 1회 로드로 두 값을 함께 읽어 세대 원자성을 보장한다. */
	private record CacheEntry(List<CachedMission> snapshot, long expiresAtMillis) {
	}

	/** 스냅샷 한 줄 — 응답 DTO 와 뷰포트 판정용 정수 사각형을 함께 들고 다닌다 (MSG-398 D2). */
	private record CachedMission(MissionResponseDto dto, MissionType type, GridRange bounds) {
	}

	/** active 미션 → mission_grids 일괄 조회(2쿼리) → missionId 그룹핑 → 유형별 shape·사각형 합성(§도메인 1). */
	private List<CachedMission> recompute() {
		List<Mission> missions = missionRepository.findActive(LocalDateTime.now(clock));
		if (missions.isEmpty()) {
			return List.of();
		}
		List<Long> missionIds = missions.stream().map(Mission::getId).toList();
		Map<Long, List<MissionGrid>> gridsByMission = missionGridRepository.findByMissionIds(missionIds).stream()
			.collect(Collectors.groupingBy(MissionGrid::getMissionId));

		return missions.stream()
			.map(mission -> {
				List<MissionGrid> grids = gridsByMission.getOrDefault(mission.getId(), List.of());
				return new CachedMission(
					MissionResponseDto.of(mission, buildShape(mission, grids)),
					mission.getType(),
					missionBounds(mission, grids));
			})
			.toList();
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
				return new Spot(grid.getGridId(), center.lat(), center.lon(), grid.getSeq());
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
