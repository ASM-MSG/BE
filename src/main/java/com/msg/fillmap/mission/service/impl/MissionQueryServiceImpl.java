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

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
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
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.MissionQueryService;

/**
 * 활성 미션 조회 구현 (MSG-222). active 판정 → 유형별 shape 단일 분기 합성 → 1h 전역 캐시.
 *
 * 캐시는 더블체크 락 — 읽기 경로는 volatile 락프리, 만료 감지 시에만 synchronized 로 재계산을 직렬화한다.
 * 블록 안에서 만료를 재확인해 방금 다른 스레드가 갱신했으면 그 스냅샷을 재사용하므로, 동시 만료 시 세대 역전
 * (먼저 시작한 느린 쿼리가 새 스냅샷을 덮음)과 중복 DB 조회가 사라진다.
 *
 * ponytail: 전역 단일 홀더 — Spring Cache·Caffeine·Redis 스택은 시간당 1회·수백 행 쿼리에 과하다(§설계 D3).
 * 멀티 인스턴스로 가면 인스턴스별 홀더라 최대 1h 불일치 → 그때 Redis(TTL 1h)로 이관. 현재 dev 는 단일 인스턴스 전제.
 */
@Service
@Transactional(readOnly = true)
public class MissionQueryServiceImpl implements MissionQueryService {

	private static final Duration CACHE_TTL = Duration.ofHours(1);

	/** seq 오름차순, seq NULL 스팟은 뒤로, 동일 seq 는 gridId 로 결정적 정렬(§도메인 3). */
	private static final Comparator<MissionGrid> SPOT_ORDER =
		Comparator.comparing(MissionGrid::getSeq, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(MissionGrid::getGridId);

	private final MissionRepository missionRepository;
	private final MissionGridRepository missionGridRepository;
	private final Clock clock;
	private final long ttlMillis;

	/** 재계산 직렬화 전용 락 — this 동기화 회피(외부 synchronized 간섭 차단). */
	private final Object refreshLock = new Object();

	/** 스냅샷+만료시각을 단일 volatile 로 원자 발행 — 두 값 찢어진 읽기(옛 데이터+새 만료)를 차단. */
	private volatile CacheEntry cache;

	@Autowired
	public MissionQueryServiceImpl(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository
	) {
		// systemUTC: findActive 의 :now 는 UTC 저장 start_at/end_at 과 비교된다 — 기본존(로컬 KST)이면 +9h 스큐.
		this(missionRepository, missionGridRepository, Clock.systemUTC(), CACHE_TTL.toMillis());
	}

	/** 캐시 만료 검증용 — 클럭·TTL 을 주입한다(§테스트 시나리오 캐시). */
	public MissionQueryServiceImpl(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		Clock clock,
		long ttlMillis
	) {
		this.missionRepository = missionRepository;
		this.missionGridRepository = missionGridRepository;
		this.clock = clock;
		this.ttlMillis = ttlMillis;
	}

	@Override
	public List<MissionResponseDto> getActiveMissions() {
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
			List<MissionResponseDto> recomputed = recompute();
			this.cache = new CacheEntry(recomputed, clock.millis() + ttlMillis);
			return recomputed;
		}
	}

	/** 스냅샷+만료시각 원자 발행 단위 — 단일 volatile 참조 1회 로드로 두 값을 함께 읽어 세대 원자성을 보장한다. */
	private record CacheEntry(List<MissionResponseDto> snapshot, long expiresAtMillis) {
	}

	/** active 미션 → mission_grids 일괄 조회(2쿼리) → missionId 그룹핑 → 유형별 shape 합성(§도메인 1). */
	private List<MissionResponseDto> recompute() {
		List<Mission> missions = missionRepository.findActive(LocalDateTime.now(clock));
		if (missions.isEmpty()) {
			return List.of();
		}
		List<Long> missionIds = missions.stream().map(Mission::getId).toList();
		Map<Long, List<MissionGrid>> gridsByMission = missionGridRepository.findByMissionIds(missionIds).stream()
			.collect(Collectors.groupingBy(MissionGrid::getMissionId));

		return missions.stream()
			.map(mission -> MissionResponseDto.of(
				mission, buildShape(mission, gridsByMission.getOrDefault(mission.getId(), List.of()))))
			.toList();
	}

	/** 유형 → shape 단일 분기(전략 클래스 없음, §도메인 3). */
	private MissionShape buildShape(Mission mission, List<MissionGrid> grids) {
		return switch (mission.getType()) {
			case COURSE -> pathShape(mission, grids);
			case AREA -> new RegionShape(mission.getRegionCode());
			case EVENT -> boxShape(grids);
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
