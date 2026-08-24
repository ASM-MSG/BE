package com.msg.fillmap.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.dto.EventLocationResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceChipResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceDetailResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceDetailResponseDto.PreviousOccurrenceDto;
import com.msg.fillmap.event.dto.GridEventLocationResponseDto;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventLocationGrid;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventStatus;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventLocationVideoCount;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridRange;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 행사 조회 구현 (MSG-439). 상태 파생과 노출 판정을 네 조회가 공유하고(statusAt · isVisible), 표시명 재료는
 * 위치 목록 단위로 한 번에 조달한다 — 구역은 요청당 리졸버 1회(MSG-341 계약), 행정동은 대표 격자 전체를
 * 묶은 벌크 호출 1회(GridQueryService.resolveRegionNames)라 위치 수만큼 쿼리가 늘지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class EventQueryServiceImpl implements EventQueryService {

	/** 칩에 담기는 상태 — 유예·아카이브 회차는 상세·역조회로만 접근한다 (§API 1). */
	private static final Set<EventStatus> CHIP_STATUSES = Set.of(EventStatus.UPCOMING, EventStatus.LIVE);

	/**
	 * 역조회 정렬 1차 키 (§API 4) — 첫 항목이 행사방 진입 기본값이라 "지금"이 앞이다. 예정이 유예보다 앞인
	 * 것은 이전 회차 유예 중 + 새 회차 예정이 공존할 때 새 회차가 현재이기 때문이다 (PRD US-010).
	 */
	private static final List<EventStatus> REVERSE_LOOKUP_PRIORITY =
		List.of(EventStatus.LIVE, EventStatus.UPCOMING, EventStatus.UPLOAD_GRACE, EventStatus.ARCHIVED);

	/** 칩 정렬 — 시 이름 → 시작일 → id 타이브레이커. FE 가 시 칩 뒤에 행사 칩을 나열하는 그룹핑의 안정 재료다. */
	private static final Comparator<EventOccurrence> CHIP_ORDER =
		Comparator.comparing(EventOccurrence::getCityName)
			.thenComparing(EventOccurrence::getStartsAt)
			.thenComparing(EventOccurrence::getId);

	/**
	 * 투영 보정 — viewportRange 는 꼭짓점 네 점만 환산해 min/max 를 잡는데, 뷰포트가 중앙자오선(경도 127.5도)을
	 * 품으면 남쪽 변이 두 귀퉁이보다 처져(실측 최대 이탈 29.6m) 남쪽 한 행이 통째로 빠진다. 0.5도 상한 안에서
	 * 이탈이 셀 한 변(100m)보다 작아 1칸이면 반드시 덮는다 (MissionQueryServiceImpl PROJECTION_PAD_CELLS 선례).
	 */
	private static final long PROJECTION_PAD_CELLS = 1;

	/** 뷰포트 한 변의 위경도 span 상한(도) — 격자·미션 조회와 같은 값·같은 전환점이다. 정확히 0.5도는 허용. */
	private static final double MAX_VIEWPORT_SPAN_DEG = 0.5;

	// WGS84 좌표계 자체의 정의역. 한국 밖이지만 정의역 안인 bbox 는 오류가 아니라 빈 배열이다.
	private static final double MIN_LATITUDE_DEG = -90.0;
	private static final double MAX_LATITUDE_DEG = 90.0;
	private static final double MIN_LONGITUDE_DEG = -180.0;
	private static final double MAX_LONGITUDE_DEG = 180.0;

	private final EventOccurrenceRepository occurrenceRepository;
	private final EventLocationRepository locationRepository;
	private final EventLocationGridRepository locationGridRepository;
	private final EventVideoRepository eventVideoRepository;
	private final GridQueryService gridQueryService;
	private final ZoneNameQueryService zoneNameQueryService;
	private final EventNotificationService eventNotificationService;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — clock 을 Clock.systemUTC() 로 고정해 전체 생성자로 위임한다 (BadgeAwardServiceImpl
	 * 선례). 전체 생성자는 상태 경계 검증용 고정 클럭 주입에 쓴다. UTC 인 이유는 starts_at·ends_at 이
	 * UTC 로 저장되기 때문이다 — 기본존(로컬 KST)이면 상태 판정이 9시간 어긋난다.
	 */
	@Autowired
	public EventQueryServiceImpl(
		EventOccurrenceRepository occurrenceRepository,
		EventLocationRepository locationRepository,
		EventLocationGridRepository locationGridRepository,
		EventVideoRepository eventVideoRepository,
		GridQueryService gridQueryService,
		ZoneNameQueryService zoneNameQueryService,
		EventNotificationService eventNotificationService
	) {
		this(occurrenceRepository, locationRepository, locationGridRepository, eventVideoRepository,
			gridQueryService, zoneNameQueryService, eventNotificationService, Clock.systemUTC());
	}

	public EventQueryServiceImpl(
		EventOccurrenceRepository occurrenceRepository,
		EventLocationRepository locationRepository,
		EventLocationGridRepository locationGridRepository,
		EventVideoRepository eventVideoRepository,
		GridQueryService gridQueryService,
		ZoneNameQueryService zoneNameQueryService,
		EventNotificationService eventNotificationService,
		Clock clock
	) {
		this.occurrenceRepository = occurrenceRepository;
		this.locationRepository = locationRepository;
		this.locationGridRepository = locationGridRepository;
		this.eventVideoRepository = eventVideoRepository;
		this.gridQueryService = gridQueryService;
		this.zoneNameQueryService = zoneNameQueryService;
		this.eventNotificationService = eventNotificationService;
		this.clock = clock;
	}

	@Override
	public List<EventOccurrenceChipResponseDto> getOccurrencesInViewport(ViewportBounds bounds) {
		validateBounds(bounds);
		LocalDateTime now = LocalDateTime.now(clock);
		GridRange view = GridEncoder.viewportRange(bounds);
		// 노출 시작분 전량을 읽어 자바에서 거른다 — 행사 행이 도시당 한둘이라 공간 쿼리·인덱스를 만들지 않는다.
		return occurrenceRepository.findByVisibleFromLessThanEqual(now).stream()
			.filter(occurrence -> CHIP_STATUSES.contains(occurrence.statusAt(now)))
			.filter(occurrence -> intersects(occurrence, view))
			.sorted(CHIP_ORDER)
			.map(occurrence -> EventOccurrenceChipResponseDto.of(occurrence, occurrence.statusAt(now)))
			.toList();
	}

	@Override
	public EventOccurrenceDetailResponseDto getOccurrenceDetail(long occurrenceId, Long userId) {
		LocalDateTime now = LocalDateTime.now(clock);
		EventOccurrence occurrence = occurrenceRepository.findWithSeriesById(occurrenceId)
			.filter(found -> isVisible(found, now))
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_NOT_FOUND));
		List<PreviousOccurrenceDto> previous = occurrenceRepository
			.findBySeriesIdAndStartsAtLessThanOrderByStartsAtDescIdDesc(
				occurrence.getSeries().getId(), occurrence.getStartsAt())
			.stream()
			.filter(candidate -> isVisible(candidate, now))
			.map(PreviousOccurrenceDto::from)
			.toList();
		// 노출 상태는 행 존재가 아니라 파생값이다 (MSG-442) — 비로그인은 항상 false 고, 종료된 회차는
		// 구독 행이 남아 있어도 false 다. now 를 넘겨 status 와 같은 시각으로 판정한다 — 두 서비스가 각자
		// Clock 을 읽으면 경계 근처에서 "진행 중인데 구독이 꺼져 보이는" 응답이 나온다.
		boolean notificationOn = eventNotificationService.isSubscribed(userId, occurrenceId, now);
		return EventOccurrenceDetailResponseDto.of(occurrence, occurrence.statusAt(now), notificationOn, previous);
	}

	@Override
	public List<EventLocationResponseDto> getLocations(long occurrenceId) {
		LocalDateTime now = LocalDateTime.now(clock);
		occurrenceRepository.findById(occurrenceId)
			.filter(found -> isVisible(found, now))
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_NOT_FOUND));
		List<EventLocation> locations = locationRepository.findByOccurrenceIdOrderByDisplayOrderAscIdAsc(occurrenceId);
		if (locations.isEmpty()) {
			return List.of();
		}
		List<Long> locationIds = locations.stream().map(EventLocation::getId).toList();
		Map<Long, List<String>> gridIds = locationGridRepository
			.findByIdEventLocationIdInOrderByIdGridIdAsc(locationIds).stream()
			.collect(Collectors.groupingBy(EventLocationGrid::getEventLocationId,
				Collectors.mapping(EventLocationGrid::getGridId, Collectors.toList())));
		// 집계에 없는 위치는 영상이 0건인 위치다 — 위치 루프 안 개별 count 는 N+1 이라 금지다 (§API 3).
		Map<Long, Long> videoCounts = eventVideoRepository.countVisibleByLocationIds(locationIds).stream()
			.collect(Collectors.toMap(EventLocationVideoCount::locationId, EventLocationVideoCount::videoCount));
		Map<String, DisplayName> displayNames = displayNames(locations);
		return locations.stream()
			.map(location -> toLocationDto(location, gridIds, videoCounts, displayNames))
			.toList();
	}

	@Override
	public Map<Long, List<LocationPoint>> getLocationsBulk(Collection<Long> occurrenceIds) {
		if (occurrenceIds.isEmpty()) {
			return Map.of();
		}
		LocalDateTime now = LocalDateTime.now(clock);
		// 미노출 회차는 키 자체가 빠진다 — 단건 조회의 13404 은닉과 같은 결이다. occurrence 는 fetch join 으로
		// 이미 로딩돼 있어 노출 판정·그룹핑이 추가 쿼리를 만들지 않는다.
		return locationRepository.findWithOccurrenceByOccurrenceIdIn(occurrenceIds).stream()
			.filter(location -> isVisible(location.getOccurrence(), now))
			.collect(Collectors.groupingBy(location -> location.getOccurrence().getId(), LinkedHashMap::new,
				Collectors.mapping(
					location -> new LocationPoint(location.getName(), location.getRepresentativeGridId()),
					Collectors.toList())));
	}

	@Override
	public List<GridEventLocationResponseDto> getLocationsByGrid(String gridId) {
		LocalDateTime now = LocalDateTime.now(clock);
		// 미노출 예정 회차는 배열에서 아예 뺀다 — 노출 전 회차는 존재 자체가 비공개 정보다 (§API 4).
		List<EventLocation> locations = locationGridRepository.findLocationsByGridId(gridId).stream()
			.filter(location -> isVisible(location.getOccurrence(), now))
			.sorted(reverseLookupOrder(now))
			.toList();
		if (locations.isEmpty()) {
			return List.of();
		}
		Map<String, DisplayName> displayNames = displayNames(locations);
		return locations.stream()
			.map(location -> toGridLocationDto(location, now, displayNames))
			.toList();
	}

	private EventLocationResponseDto toLocationDto(EventLocation location, Map<Long, List<String>> gridIds,
		Map<Long, Long> videoCounts, Map<String, DisplayName> displayNames) {
		DisplayName name = displayNames.get(location.getRepresentativeGridId());
		return new EventLocationResponseDto(
			location.getId(),
			location.getName(),
			location.getType().name(),
			location.getOperatingHours(),
			gridIds.getOrDefault(location.getId(), List.of()),
			location.getRepresentativeGridId(),
			name.zoneName(),
			name.zoneCell(),
			name.regionName(),
			videoCounts.getOrDefault(location.getId(), 0L));
	}

	private GridEventLocationResponseDto toGridLocationDto(EventLocation location, LocalDateTime now,
		Map<String, DisplayName> displayNames) {
		EventOccurrence occurrence = location.getOccurrence();
		DisplayName name = displayNames.get(location.getRepresentativeGridId());
		return new GridEventLocationResponseDto(
			occurrence.getId(),
			occurrence.getTitle(),
			occurrence.statusAt(now).name(),
			location.getId(),
			location.getName(),
			location.getRepresentativeGridId(),
			name.zoneName(),
			name.zoneCell(),
			name.regionName());
	}

	/**
	 * 대표 격자 기준 표시명 재료 (§공통). 구역은 요청당 리졸버 1회로 순수 계산하고(MSG-341 이 N+1 을 계약
	 * 형태로 막아 둔 사용법), 행정동은 대표 격자를 한 번에 넘겨 받는다. grids 행이 없는 격자도 중심점
	 * 재판정으로 이름이 나오고(resolveRegionNames 계약), 진짜 무귀속이면 키가 없어 null 이 된다.
	 */
	private Map<String, DisplayName> displayNames(List<EventLocation> locations) {
		List<String> gridIds = locations.stream().map(EventLocation::getRepresentativeGridId).distinct().toList();
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		Map<String, String> regionNames = gridQueryService.resolveRegionNames(gridIds);
		Map<String, DisplayName> names = new LinkedHashMap<>();
		for (String gridId : gridIds) {
			GridIndex index = GridEncoder.decode(gridId);
			ZoneCellName zone = resolver.name(index.gridY(), index.gridX());
			names.put(gridId, new DisplayName(zone.zoneName(), zone.zoneCell(), regionNames.get(gridId)));
		}
		return names;
	}

	/**
	 * 역조회 정렬 (§API 4) — 상태 우선순위 → 상태별 방향이 다른 startsAt → 회차 id 내림차순 → 위치 id
	 * 오름차순. 마지막 키는 같은 회차의 두 위치가 한 격자에 걸린 비정상 데이터에서도 순서를 결정적이게 하는
	 * 보험이다 (정상 데이터는 UNIQUE 제약이 회차당 한 위치를 보장해 도달하지 않는다).
	 */
	private Comparator<EventLocation> reverseLookupOrder(LocalDateTime now) {
		return Comparator
			.comparingInt((EventLocation location) ->
				REVERSE_LOOKUP_PRIORITY.indexOf(location.getOccurrence().statusAt(now)))
			.thenComparing((left, right) -> compareStartsAt(left, right, now))
			.thenComparing(Comparator.comparing(
				(EventLocation location) -> location.getOccurrence().getId(), Comparator.reverseOrder()))
			.thenComparing(EventLocation::getId);
	}

	/**
	 * 동순위 보조 정렬의 방향은 상태마다 다르다 — 예정끼리는 가장 임박한 회차가 "다음 행사"라 오름차순이고,
	 * 나머지는 최근 기록이 우선이라 내림차순이다. 1차 키가 같을 때만 불리므로 두 인자의 상태는 항상 같다.
	 */
	private int compareStartsAt(EventLocation left, EventLocation right, LocalDateTime now) {
		LocalDateTime leftStartsAt = left.getOccurrence().getStartsAt();
		LocalDateTime rightStartsAt = right.getOccurrence().getStartsAt();
		if (left.getOccurrence().statusAt(now) == EventStatus.UPCOMING) {
			return leftStartsAt.compareTo(rightStartsAt);
		}
		return rightStartsAt.compareTo(leftStartsAt);
	}

	/**
	 * 노출 판정 — 아직 노출 시작 전인 예정 회차만 감춘다 (§API 명세). 정의는 엔티티가 갖는다
	 * ({@link EventOccurrence#isVisibleAt}) — 알림 구독(MSG-442)도 같은 술어를 써야 미공개 회차의 존재가
	 * 한쪽 경로로만 새지 않는다.
	 */
	private boolean isVisible(EventOccurrence occurrence, LocalDateTime now) {
		return occurrence.isVisibleAt(now);
	}

	/** 노출 영역 사각형과 뷰포트(보정 포함)의 정수 부등식 겹침 판정 — 과다 포함 쪽으로만 틀리고 누락이 없다. */
	private boolean intersects(EventOccurrence occurrence, GridRange view) {
		return occurrence.getMaxGridY() >= view.minGridY() - PROJECTION_PAD_CELLS
			&& occurrence.getMinGridY() <= view.maxGridY() + PROJECTION_PAD_CELLS
			&& occurrence.getMaxGridX() >= view.minGridX() - PROJECTION_PAD_CELLS
			&& occurrence.getMinGridX() <= view.maxGridX() + PROJECTION_PAD_CELLS;
	}

	/**
	 * bbox 검증 — 좌표 유효성 → 뒤집힘 → span 상한 순서, 상한은 정확히 0.5도까지 허용이다. 좌표 검증이 맨
	 * 앞인 이유는 NaN 이 어떤 비교도 false 라 뒤 검사를 그대로 통과하고(fail-open), 1e308 급 유한값이
	 * viewportRange 의 Proj4J 경도 정규화를 사실상 무한 루프로 만들기 때문이다.
	 * ponytail: MissionQueryServiceImpl.validateBounds 의 쌍둥이(세 번째 복제)다. 공통 validator 승격은
	 * 2026-09-07 멘토 라이브 코드 리뷰 전 구조 변경 금지 합의로 의도적으로 유예했고(MSG-439 §API 1),
	 * 유예의 조건이 동일 순서·동일 상수 유지다.
	 */
	private void validateBounds(ViewportBounds bounds) {
		if (!isValidCoordinates(bounds)) {
			throw new ApiException(EventErrorCode.INVALID_VIEWPORT);
		}
		if (bounds.swLat() > bounds.neLat() || bounds.swLng() > bounds.neLng()) {
			throw new ApiException(EventErrorCode.INVALID_VIEWPORT);
		}
		double latSpan = bounds.neLat() - bounds.swLat();
		double lngSpan = bounds.neLng() - bounds.swLng();
		if (latSpan > MAX_VIEWPORT_SPAN_DEG || lngSpan > MAX_VIEWPORT_SPAN_DEG) {
			throw new ApiException(EventErrorCode.VIEWPORT_TOO_LARGE);
		}
	}

	/** 네 좌표가 모두 WGS84 유효 범위 안인지 — 범위 비교가 NaN(두 비교 모두 false)·±무한대까지 걸러낸다. */
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

	/** 대표 격자 하나의 표시명 재료 묶음 — 구역 두 값은 항상 쌍이고, 행정동은 폴백이라 서로 독립이다. */
	private record DisplayName(String zoneName, String zoneCell, String regionName) {
	}
}
