package com.msg.fillmap.event.seed;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventLocationGrid;
import com.msg.fillmap.event.entity.EventLocationType;
import com.msg.fillmap.event.entity.EventNotificationSubscription;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventNotificationSubscriptionRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.global.geo.AreaCell;
import com.msg.fillmap.global.geo.RepresentativeGridResolver;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;

/**
 * 행사 시더 (MSG-438, ZoneSeeder 선례). 기본 off — {@code fillmap.event.seed.enabled=true} 일 때만
 * 기동 시 1회 seed/events.json 을 자연키(series_key·occurrence_key·location_key)로 멱등 UPSERT 한다.
 * <p>
 * zones 의 native UPSERT 와 달리 JPA 조회 후 저장이다 — 격자 영역 대조(추가 insert·제거 delete)를 위해
 * 읽기가 어차피 필요하고 ON CONFLICT 같은 PostgreSQL 전용 기능이 필요한 지점이 없다.
 * 전체가 트랜잭션 하나라 두 지연 제약(회차 내 격자 유일, 대표 격자 영역 소속)이 커밋 시점에 함께 검증된다.
 * <p>
 * {@code @Order(30)}: 타 시더와 FK 의존은 없지만 결정성을 위해 Region(10) · Zone(20) 다음에 둔다.
 */
@Slf4j
@Component
@Order(30)
public class EventSeeder implements ApplicationRunner {

	/** 위치당 고유 셀 상한 (5km × 5km 상당) — zones 의 남북 26행 캡과 같은 성격의 입력 상한. */
	static final int MAX_CELLS_PER_LOCATION = 2500;

	/** EPSG:5179 국내 정의역을 넉넉히 포함하는 방어 상한. 정수 제곱 산술의 오버플로 여유도 여기서 나온다. */
	private static final int GRID_INDEX_UPPER_EXCLUSIVE = 100_000;

	/** 일정 변경 알림 문구 (MSG-442 발송 표 확정값). */
	private static final String SCHEDULE_CHANGED_BODY = "행사 일정이 변경됐어요. 새 일정을 확인해 보세요";

	private final EventSeriesRepository seriesRepository;
	private final EventOccurrenceRepository occurrenceRepository;
	private final EventLocationRepository locationRepository;
	private final EventLocationGridRepository locationGridRepository;
	private final EventVideoRepository eventVideoRepository;
	private final EventNotificationSubscriptionRepository subscriptionRepository;
	private final NotificationCommandService notificationCommandService;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Autowired
	public EventSeeder(EventSeriesRepository seriesRepository, EventOccurrenceRepository occurrenceRepository,
		EventLocationRepository locationRepository, EventLocationGridRepository locationGridRepository,
		EventVideoRepository eventVideoRepository, EventNotificationSubscriptionRepository subscriptionRepository,
		NotificationCommandService notificationCommandService, ObjectMapper objectMapper) {
		this(seriesRepository, occurrenceRepository, locationRepository, locationGridRepository,
			eventVideoRepository, subscriptionRepository, notificationCommandService, objectMapper,
			Clock.systemUTC());
	}

	/** 종료 경계 통과 판정용 고정 Clock 주입 (MSG-442, EventQueryServiceImpl 선례). UTC 인 이유는 회차 시각이 UTC 저장이라서다. */
	public EventSeeder(EventSeriesRepository seriesRepository, EventOccurrenceRepository occurrenceRepository,
		EventLocationRepository locationRepository, EventLocationGridRepository locationGridRepository,
		EventVideoRepository eventVideoRepository, EventNotificationSubscriptionRepository subscriptionRepository,
		NotificationCommandService notificationCommandService, ObjectMapper objectMapper, Clock clock) {
		this.seriesRepository = seriesRepository;
		this.occurrenceRepository = occurrenceRepository;
		this.locationRepository = locationRepository;
		this.locationGridRepository = locationGridRepository;
		this.eventVideoRepository = eventVideoRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.notificationCommandService = notificationCommandService;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Value("${fillmap.event.seed.enabled:false}")
	private boolean enabled;

	@Value("${fillmap.event.seed.path:seed/events.json}")
	private String path;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}
		int loaded = seed(new ClassPathResource(path));
		log.info("행사 시딩 완료 — 위치 {} 건 UPSERT", loaded);
	}

	/**
	 * JSON 배열을 읽어 시리즈·회차·위치·격자를 멱등 UPSERT 한다. 트랜잭션은 호출자(run 또는 테스트)가 연다.
	 * 적재한 행사 위치 수를 반환한다.
	 */
	public int seed(Resource resource) {
		List<EventSeed> seeds = read(resource);
		// 롤링 배포로 두 인스턴스가 동시에 돌 때의 자연키 UNIQUE 충돌을 막는다. 트랜잭션 종료 시 자동 해제.
		// MSG-442 의 종료 구독 정리 틱도 같은 키를 잡으므로 시더와 정리가 한 줄로 직렬화된다.
		seriesRepository.acquireEventWriteLock();
		// 락 획득 뒤에 읽는다 — 대기 중 정리 틱이 커밋했을 수 있어 그 이전 시각으로 판정하면 창이 어긋난다.
		LocalDateTime now = LocalDateTime.now(clock);

		int locations = 0;
		for (EventSeed seed : seeds) {
			EventSeries series = upsertSeries(seed);
			for (EventSeed.Occurrence occurrenceSeed : orEmpty(seed.occurrences())) {
				EventOccurrence occurrence = upsertOccurrence(series, occurrenceSeed, now);
				for (EventSeed.Location locationSeed : orEmpty(occurrenceSeed.locations())) {
					upsertLocation(occurrence, locationSeed);
					locations++;
				}
			}
		}
		return locations;
	}

	private EventSeries upsertSeries(EventSeed seed) {
		EventSeries series = seriesRepository.findBySeriesKey(seed.seriesKey())
			.orElseGet(() -> new EventSeries(seed.seriesKey(), seed.seriesName()));
		series.updateName(seed.seriesName());
		return seriesRepository.save(series);
	}

	private EventOccurrence upsertOccurrence(EventSeries series, EventSeed.Occurrence seed, LocalDateTime now) {
		EventSeed.Rect exposure = seed.exposure();
		validateRect(exposure, seed.occurrenceKey() + " 노출 영역");

		EventOccurrence occurrence = occurrenceRepository.findByOccurrenceKey(seed.occurrenceKey())
			.orElseGet(() -> new EventOccurrence(series, seed.occurrenceKey()));
		releaseSubscriptionsIfEnded(occurrence, now);

		int revisionBefore = occurrence.getScheduleRevision();
		occurrence.update(series, seed.title(), seed.cityName(),
			toUtc(seed.startsAt(), seed.occurrenceKey()), toUtc(seed.endsAt(), seed.occurrenceKey()),
			exposure.minGridY(), exposure.maxGridY(), exposure.minGridX(), exposure.maxGridX());
		EventOccurrence saved = occurrenceRepository.save(occurrence);
		if (saved.getScheduleRevision() > revisionBefore) {
			notifyScheduleChanged(saved);
		}
		return saved;
	}

	/**
	 * 종료 경계를 지난 회차를 건드리는 재시드는 새 값과 무관하게 구독을 먼저 지운다 (MSG-442).
	 * 구독 해제 시점을 정리 틱 타이밍이 아니라 종료 경계 통과 하나로 결정하기 위해서다 — 부활 연장이면
	 * 정리 틱의 no-op 으로 살아남은 행이 노출 상태를 다시 ON 으로 만들고, 종료를 유지하는 변경이면 정리 틱
	 * 전 창의 행이 일정 변경 알림을 받는다. 둘 다 논리적으로 이미 해제된 사용자다.
	 */
	private void releaseSubscriptionsIfEnded(EventOccurrence occurrence, LocalDateTime now) {
		if (occurrence.getId() == null || occurrence.getEndsAt().isAfter(now)) {
			return;
		}
		subscriptionRepository.deleteByOccurrenceId(occurrence.getId());
	}

	/**
	 * 일정 변경 알림 (MSG-442). 시딩 트랜잭션 안이라 outbox 의 원자성 설계(비즈니스 커밋과 같은 커밋)에
	 * 정확히 부합하고, 재실행돼도 같은 개정 번호는 같은 dedupe 키라 흡수된다. 키가 시각값이 아니라 개정
	 * 번호인 이유는 일정 왕복(A→B→A→B)에서 두 번째 변경 알림이 억제되지 않게 하기 위해서다.
	 */
	private void notifyScheduleChanged(EventOccurrence occurrence) {
		String eventKey = "EVENT_SCHEDULE:%s:%d".formatted(
			occurrence.getOccurrenceKey(), occurrence.getScheduleRevision());
		for (EventNotificationSubscription subscription :
			subscriptionRepository.findAllByIdEventOccurrenceId(occurrence.getId())) {
			notificationCommandService.record(subscription.getId().getUserId(), NotificationCategory.EVENT,
				eventKey, occurrence.getTitle(), SCHEDULE_CHANGED_BODY);
		}
	}

	private void upsertLocation(EventOccurrence occurrence, EventSeed.Location seed) {
		Set<AreaCell> cells = expand(seed);
		String representativeGridId = RepresentativeGridResolver.resolve(cells, seed.representativeGridId());

		// 대표 격자를 재계산하기 전에 기존 행을 잠근다 — MSG-440 업로드가 같은 행을 같은 순서로 잠근다.
		Optional<EventLocation> existing = locationRepository.findWithLockByLocationKey(seed.locationKey());
		existing.ifPresent(location -> guardRepresentativeGridDrift(location, representativeGridId, seed));

		EventLocation location = existing.orElseGet(() -> new EventLocation(occurrence, seed.locationKey()));
		location.update(occurrence, seed.name(), EventLocationType.valueOf(seed.type()), seed.operatingHours(),
			seed.displayOrder() == null ? 0 : seed.displayOrder(), representativeGridId);
		locationRepository.save(location);

		syncGrids(location, occurrence, cells);
	}

	/**
	 * 영상이 붙은 위치의 대표 격자 변경을 거부한다. 기존 영상은 옛 격자에, 신규 영상은 새 격자에 붙어
	 * 위치의 피드와 격자 노출이 갈라지는 드리프트를 막기 위해서다. 영상 이전은 도입하지 않는다.
	 */
	private void guardRepresentativeGridDrift(EventLocation location, String representativeGridId,
		EventSeed.Location seed) {
		if (representativeGridId.equals(location.getRepresentativeGridId())) {
			return;
		}
		if (eventVideoRepository.existsByLocationId(location.getId())) {
			throw new IllegalStateException(
				"행사 위치 %s 에 이미 영상이 있어 대표 격자를 %s 에서 %s 로 바꿀 수 없습니다".formatted(
					seed.locationKey(), location.getRepresentativeGridId(), representativeGridId));
		}
	}

	/** 위치 안의 격자 행만 파일과 완전 일치로 동기화한다 (시리즈·회차·위치 자체는 삭제하지 않는다). */
	private void syncGrids(EventLocation location, EventOccurrence occurrence, Set<AreaCell> cells) {
		Set<String> desired = new HashSet<>();
		for (AreaCell cell : cells) {
			desired.add(cell.gridId());
		}

		Set<String> current = new HashSet<>();
		for (EventLocationGrid row : locationGridRepository.findByIdEventLocationId(location.getId())) {
			if (desired.contains(row.getGridId())) {
				current.add(row.getGridId());
			} else {
				locationGridRepository.delete(row);
			}
		}
		for (String gridId : desired) {
			if (!current.contains(gridId)) {
				locationGridRepository.save(new EventLocationGrid(location.getId(), occurrence.getId(), gridId));
			}
		}
	}

	/**
	 * 사각형 합집합을 셀 단위로 펼쳐 중복을 제거한다. 겹침은 합집합 의미라 허용하고 중복으로 세지 않으므로,
	 * 같은 영역의 다른 표현이 다른 판정을 받지 않는다(표현 무관 계약).
	 */
	private Set<AreaCell> expand(EventSeed.Location seed) {
		List<EventSeed.Rect> rects = seed.areaRects();
		if (rects == null || rects.isEmpty()) {
			throw new IllegalStateException("행사 위치 %s 의 areaRects 가 비어 있습니다".formatted(seed.locationKey()));
		}

		Set<AreaCell> cells = new LinkedHashSet<>();
		for (EventSeed.Rect rect : rects) {
			validateRect(rect, seed.locationKey());
			long rows = (long) rect.maxGridY() - rect.minGridY() + 1;
			long columns = (long) rect.maxGridX() - rect.minGridX() + 1;
			// 단일 사각형의 고유 셀 수는 정확히 행 × 열 이라 전개 전에 거부해도 과잉 거부가 없다 (OOM 1차 가드).
			if (rows * columns > MAX_CELLS_PER_LOCATION) {
				throw new IllegalStateException("행사 위치 %s 의 사각형 하나가 상한 %d 칸을 넘습니다 (%d 칸)".formatted(
					seed.locationKey(), MAX_CELLS_PER_LOCATION, rows * columns));
			}
			for (int gridY = rect.minGridY(); gridY <= rect.maxGridY(); gridY++) {
				for (int gridX = rect.minGridX(); gridX <= rect.maxGridX(); gridX++) {
					cells.add(new AreaCell(gridY, gridX));
					if (cells.size() > MAX_CELLS_PER_LOCATION) {
						throw new IllegalStateException("행사 위치 %s 의 고유 셀 수가 상한 %d 칸을 넘습니다".formatted(
							seed.locationKey(), MAX_CELLS_PER_LOCATION));
					}
				}
			}
		}
		return cells;
	}

	private void validateRect(EventSeed.Rect rect, String context) {
		if (rect == null) {
			throw new IllegalStateException("%s 의 격자 사각형이 없습니다".formatted(context));
		}
		requireIndex(rect.minGridY(), context);
		requireIndex(rect.maxGridY(), context);
		requireIndex(rect.minGridX(), context);
		requireIndex(rect.maxGridX(), context);
		// 역전은 전개 결과가 빈 집합이 되어 오류가 조용히 숨는다.
		if (rect.minGridY() > rect.maxGridY() || rect.minGridX() > rect.maxGridX()) {
			throw new IllegalStateException("%s 의 격자 사각형이 역전됐습니다 (min > max)".formatted(context));
		}
	}

	private void requireIndex(Integer index, String context) {
		if (index == null || index <= 0 || index >= GRID_INDEX_UPPER_EXCLUSIVE) {
			throw new IllegalStateException("%s 의 격자 인덱스 %s 가 허용 범위(0 초과 %d 미만) 밖입니다".formatted(
				context, index, GRID_INDEX_UPPER_EXCLUSIVE));
		}
	}

	/**
	 * 오프셋 필수 ISO-8601 을 UTC LocalDateTime 으로 변환한다. 시드 작성자는 KST 로 생각하는데 naive 표기를
	 * 허용하면 "naive = UTC" 관례(MSG-376)와 충돌해 9시간 어긋난 잠복 버그가 되므로 파싱 단계에서 막는다.
	 */
	private LocalDateTime toUtc(String value, String context) {
		if (value == null) {
			throw new IllegalStateException("행사 회차 %s 의 시각이 비어 있습니다".formatted(context));
		}
		try {
			return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
		} catch (DateTimeParseException e) {
			throw new IllegalStateException(
				"행사 회차 %s 의 시각은 오프셋 포함 ISO-8601 이어야 합니다: %s".formatted(context, value), e);
		}
	}

	private List<EventSeed> read(Resource resource) {
		try (InputStream in = resource.getInputStream()) {
			return List.of(objectMapper.readValue(in, EventSeed[].class));
		} catch (IOException e) {
			throw new IllegalStateException("행사 시드 파일을 읽을 수 없습니다: " + resource.getDescription(), e);
		}
	}

	private <T> List<T> orEmpty(List<T> values) {
		return values == null ? List.of() : values;
	}
}
