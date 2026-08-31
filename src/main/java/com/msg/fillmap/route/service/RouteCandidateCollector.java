package com.msg.fillmap.route.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.event.dto.EventOccurrenceChipResponseDto;
import com.msg.fillmap.event.service.EventQueryService;
import com.msg.fillmap.event.service.EventQueryService.LocationPoint;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.dto.MissionShape;
import com.msg.fillmap.mission.dto.MissionShape.BoxShape;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.route.service.RouteCandidate.Kind;
import com.msg.fillmap.route.service.RouteIntentClient.ParsedIntent;
import com.msg.fillmap.search.service.PlaceSearchService;

/**
 * 후보 수집 (MSG-457 §도메인 로직 1). 기존 조회를 읽기로만 재사용하고 후보는 세 출처(활성 미션·행사·장소
 * 검색 실조회)에서만 나온다(FR-ROUTE-03) — 해석 결과의 interests 는 검색어 재료, preferred_order 는 순서
 * 힌트일 뿐 어느 쪽도 직접 후보를 만들지 못한다(NFR-SEC-08). 전 구간 읽기 전용이라 스탬프·미션 진행
 * 데이터를 건드리지 않는다(FR-ROUTE-09).
 */
@Slf4j
@Component
public class RouteCandidateCollector {

	/** 지점 수 상한 (FR-ROUTE-13) — 하루에 다닐 수 있는 분량, AI explain 방어 상한 20의 충분히 안쪽. */
	static final int MAX_POINTS = 8;

	/** 장소 검색 결과 중 후보로 쓰는 상위 건수 (§도메인 로직 1). 호출은 요청당 최대 1회 — 상한 산술의 전제. */
	private static final int PLACE_RESULT_LIMIT = 3;

	/** 해석 기간 겹침 판정의 앞뒤 여유(일) — 모델의 "이번 주말" 해석이 이틀 어긋난 실측(MSG-458 리포트). */
	private static final long PERIOD_SLACK_DAYS = 2;

	/** 해석 period 의 일 경계 — KST 날짜 라벨(AI 계약, glossary 스트릭 일 경계와 동일). */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 추천 후보가 되는 미션 유형 — 화면 칩이 있는 세 종류만(§도메인 로직 1). */
	private static final List<MissionType> CANDIDATE_MISSION_TYPES =
		List.of(MissionType.EVENT, MissionType.POPUP, MissionType.COURSE);

	private final MissionQueryService missionQueryService;
	private final EventQueryService eventQueryService;
	private final PlaceSearchService placeSearchService;
	private final GridQueryService gridQueryService;
	private final ObjectMapper objectMapper;
	private final InterestMatcher interestMatcher;
	private final Clock clock;

	/** 프로덕션 생성자 — UTC 고정 위임 (EventQueryServiceImpl 선례). 전체 생성자는 재필터 검증용 고정 클럭 주입에 쓴다. */
	@Autowired
	public RouteCandidateCollector(MissionQueryService missionQueryService, EventQueryService eventQueryService,
		PlaceSearchService placeSearchService, GridQueryService gridQueryService, ObjectMapper objectMapper,
		InterestMatcher interestMatcher) {
		this(missionQueryService, eventQueryService, placeSearchService, gridQueryService, objectMapper,
			interestMatcher, Clock.systemUTC());
	}

	public RouteCandidateCollector(MissionQueryService missionQueryService, EventQueryService eventQueryService,
		PlaceSearchService placeSearchService, GridQueryService gridQueryService, ObjectMapper objectMapper,
		InterestMatcher interestMatcher, Clock clock) {
		this.missionQueryService = missionQueryService;
		this.eventQueryService = eventQueryService;
		this.placeSearchService = placeSearchService;
		this.gridQueryService = gridQueryService;
		this.objectMapper = objectMapper;
		this.interestMatcher = interestMatcher;
		this.clock = clock;
	}

	/**
	 * 미션·행사·(필요 시) 장소 검색에서 후보를 모아 선별 규칙으로 최대 8개를 고른다. 선별은 관심사 일치
	 * 우선 → 뷰포트 중심 거리순 → gridId 사전순이라 관심사가 비어도(빈 해석 포함) 뷰포트 기준 추천이
	 * 성립한다(FR-ROUTE-06).
	 */
	public List<RouteCandidate> collect(ViewportBounds bounds, ParsedIntent intent) {
		List<RouteCandidate> candidates = new ArrayList<>();
		candidates.addAll(missionCandidates(bounds, intent));
		candidates.addAll(eventCandidates(bounds, intent));
		candidates.addAll(placeCandidates(bounds, intent, candidates));
		return select(candidates, bounds);
	}

	/**
	 * 미션 후보 — 뷰포트 조회는 1시간 스냅샷 캐시라 활성 판정이 최대 1시간 낡는다(MSG-398 D1). 요청 시점
	 * Clock 으로 startAt·endAt 을 재필터해 끝난 축제를 거른다(FR-ROUTE-04, null 은 상시 미션이라 통과).
	 * 대표 좌표가 뷰포트 밖인 미션(사각형 겹침만으로 잡힌 큰 축제)은 행사 규칙과 같은 이유로 제외한다
	 * (FR-ROUTE-06 — 사용자가 보는 범위 밖 지점을 추천하지 않는다).
	 */
	private List<RouteCandidate> missionCandidates(ViewportBounds bounds, ParsedIntent intent) {
		LocalDateTime now = LocalDateTime.now(clock);
		List<RouteCandidate> candidates = new ArrayList<>();
		for (MissionType type : CANDIDATE_MISSION_TYPES) {
			for (MissionResponseDto mission : missionQueryService.getMissionsInViewport(bounds, type)) {
				if (!activeAt(mission, now) || !periodOverlaps(mission.startAt(), mission.endAt(), intent.period())) {
					continue;
				}
				GridPoint point = representativePoint(type, mission.shape());
				if (point == null || !contains(bounds, point.lat(), point.lon())) {
					continue;
				}
				Kind kind = missionKind(type);
				// 판정 재료 = 제목 + 유형 라벨 + 소개문 + 장소명 (MSG-514 — placeName 신설, §도메인 로직 1 표)
				String haystack = mission.title() + " " + typeLabel(type)
					+ (mission.description() == null ? "" : " " + mission.description())
					+ (mission.placeName() == null ? "" : " " + mission.placeName());
				candidates.add(new RouteCandidate(
					mission.title(), kind, point.lat(), point.lon(), GridEncoder.encode(point.lat(), point.lon()),
					mission.missionId(), null, mission.startAt(), mission.endAt(),
					interestMatcher.firstMatch(intent.interests(), haystack), missionDetailFacts(type, mission)));
			}
		}
		return candidates;
	}

	/**
	 * 행사 후보 — 뷰포트 조회는 statusAt 파생값이라 재필터가 필요 없다. 회차마다 뷰포트 안에 드는 위치만
	 * 남기고 정렬 첫 항목(진입 기본값 계약)을 대표 지점으로 쓰며, 안에 드는 위치가 하나도 없는 회차
	 * (bbox 교차만으로 잡힌 BIFF류)는 제외한다(FR-ROUTE-06).
	 */
	private List<RouteCandidate> eventCandidates(ViewportBounds bounds, ParsedIntent intent) {
		List<EventOccurrenceChipResponseDto> chips = eventQueryService.getOccurrencesInViewport(bounds);
		if (chips.isEmpty()) {
			return List.of();
		}
		Map<Long, List<LocationPoint>> locations = eventQueryService.getLocationsBulk(
			chips.stream().map(EventOccurrenceChipResponseDto::occurrenceId).toList());
		List<RouteCandidate> candidates = new ArrayList<>();
		for (EventOccurrenceChipResponseDto chip : chips) {
			if (!periodOverlaps(chip.startsAt(), chip.endsAt(), intent.period())) {
				continue;
			}
			for (LocationPoint location : locations.getOrDefault(chip.occurrenceId(), List.of())) {
				GridPoint center = GridEncoder.center(location.representativeGridId());
				if (contains(bounds, center.lat(), center.lon())) {
					// 행사 지점 고유 사실은 없음 (MSG-514 §도메인 로직 4) — 칩 DTO 에 제목과 기간뿐이라 재료가 없다.
					candidates.add(new RouteCandidate(
						chip.title(), Kind.EVENT, center.lat(), center.lon(), location.representativeGridId(),
						null, chip.occurrenceId(), chip.startsAt(), chip.endsAt(),
						interestMatcher.firstMatch(intent.interests(), chip.title() + " 행사"), List.of()));
					break;
				}
			}
		}
		return candidates;
	}

	/**
	 * 장소 검색 후보 — 관심사가 미션·행사로 안 채워질 때만 요청당 최대 1회(응답 시간 상한 산술의 전제).
	 * 검색어는 미충족 관심사 중 첫째로 "{region, 없으면 뷰포트 중심 행정동 이름} {관심사}"를 조립하고,
	 * 집계 없는 오버로드만 부른다(기계 조립 검색어가 인기 검색어를 오염시키지 않게 — 계약 변경 절).
	 * 검색 실패(5502)는 전파하지 않고 장소 후보 0으로 취급한다 — 후보 부족은 실패가 아니고(FR-ROUTE-07),
	 * 다른 도메인 에러코드가 추천 API 로 새는 것도 막는다(2026-08-24 확정).
	 */
	private List<RouteCandidate> placeCandidates(ViewportBounds bounds, ParsedIntent intent,
		List<RouteCandidate> existing) {
		// ponytail: 미충족 판정은 후보의 matchedInterest(첫 일치 관심사)만 본다 — 한 후보가 두 관심사를 다
		// 품는 경우 둘째 관심사가 미충족으로 보여 검색이 한 번 더 유발될 수 있는데, 결과가 늘어나는 쪽
		// 오차라 수용한다 (상한 8 선별은 그대로).
		String unmet = intent.interests().stream()
			.filter(interest -> !interest.isBlank())	// 빈 항목이 region 단독 광역 검색을 유발하지 않게 (Codex P2)
			.filter(interest -> existing.stream()
				.noneMatch(candidate -> interest.equals(candidate.matchedInterest())))
			.findFirst()
			.orElse(null);
		if (unmet == null) {
			return List.of();
		}
		String region = intent.region() != null && !intent.region().isBlank()
			? intent.region()
			: viewportCenterRegionName(bounds);
		String query = region == null ? unmet : region + " " + unmet;
		try {
			return placeSearchService.searchPlaces(query).stream()
				// 카카오 키워드 검색은 지역 제한이 없다 — 뷰포트 밖 장소는 미션·행사와 같은 이유로 제외한다
				// (FR-ROUTE-06). 거른 뒤 상위 3건이라 밖 결과가 안 결과의 자리를 뺏지 않는다 (Codex P1).
				.filter(place -> contains(bounds, place.lat(), place.lng()))
				.limit(PLACE_RESULT_LIMIT)
				.map(place -> new RouteCandidate(place.name(), Kind.PLACE, place.lat(), place.lng(),
					place.gridId(), null, null, null, null, unmet,
					place.address() == null ? List.of() : List.of(place.address())))
				.toList();
		} catch (ApiException e) {
			log.warn("[route] 장소 검색 실패 — 장소 후보 0으로 진행 (미션·행사 후보만으로 부분 결과): developCode={}",
				e.getErrorCode().getErrorCode());
			return List.of();
		}
	}

	/** 선별 규칙 — 관심사 일치 우선, 같은 급이면 뷰포트 중심 거리순, 그것도 같으면 gridId 사전순. 상한 8. */
	private List<RouteCandidate> select(List<RouteCandidate> candidates, ViewportBounds bounds) {
		double centerLat = (bounds.swLat() + bounds.neLat()) / 2;
		double centerLng = (bounds.swLng() + bounds.neLng()) / 2;
		return candidates.stream()
			.sorted(Comparator
				.comparing((RouteCandidate candidate) -> candidate.matchedInterest() == null)
				.thenComparingDouble(candidate ->
					RouteOrderPlanner.distanceMeters(centerLat, centerLng, candidate.lat(), candidate.lng()))
				.thenComparing(RouteCandidate::gridId))
			.limit(MAX_POINTS)
			.toList();
	}

	/** 요청 시점 활성 재필터 (FR-ROUTE-04) — null 은 상시 미션이라 통과한다. */
	private static boolean activeAt(MissionResponseDto mission, LocalDateTime now) {
		return (mission.startAt() == null || !mission.startAt().isAfter(now))
			&& (mission.endAt() == null || !mission.endAt().isBefore(now));
	}

	/**
	 * 해석 기간과의 겹침 판정 — 앞뒤 2일 여유(엄격 일치 필터를 쓰지 않는 근거는 §선별 규칙). 해석 기간이
	 * 없으면 전부 통과, 후보 기간의 null 쪽은 무기한으로 본다. 해석 period 는 KST 날짜 라벨(AI 계약)이고
	 * 후보 시각은 naive UTC 관례라, KST 로 변환해 날짜를 얻어야 KST 00~09시 구간이 전날로 오판정되지
	 * 않는다 (시각 컨벤션 MSG-376 전제 — 스트릭의 AT TIME ZONE 'Asia/Seoul' 판정과 같은 결, Codex P2).
	 */
	private static boolean periodOverlaps(LocalDateTime start, LocalDateTime end, ParsedIntent.Period period) {
		if (period == null) {
			return true;
		}
		LocalDate from = period.start().minusDays(PERIOD_SLACK_DAYS);
		LocalDate to = period.end().plusDays(PERIOD_SLACK_DAYS);
		return (start == null || !kstDate(start).isAfter(to))
			&& (end == null || !kstDate(end).isBefore(from));
	}

	/** naive UTC 시각의 KST 날짜 — 저장 관례(UTC)와 사용자 일 경계(KST)의 변환 한 곳. 기간 필터와
	 * facts 기간 표기(RouteRecommendServiceImpl)가 같이 쓴다 — 변환 지점을 흩지 않는다. */
	static LocalDate kstDate(LocalDateTime utc) {
		return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
	}

	/**
	 * 대표 좌표 (§도메인 로직 1) — 축제·팝업은 판정 사각형 중앙 셀 중심(MSG-437 귀속점 규칙 재사용), 코스는
	 * PATH 시작점이다. 좌표를 만들 수 없는 후보(빈 폴리곤·라인과 스팟 모두 없음)는 제외한다.
	 * ponytail: 축제·팝업은 스냅샷 내부의 정수 사각형이 DTO 에 없어 BoxShape 링 중점을 셀로 양자화한다 —
	 * 변 길이가 짝수 칸인 사각형에서 정수 나눗셈 내림과 반대쪽 셀을 골라 최대 한 셀(100m) 어긋날 수 있는
	 * 근사이고(홀수 칸은 중앙 셀이 유일해 일치), 마커 용도라 수용한다.
	 * 정확 일치가 필요해지면 MissionResponseDto 에 대표 격자를 실어야 한다(비파괴 추가).
	 */
	private GridPoint representativePoint(MissionType type, MissionShape shape) {
		if (type == MissionType.COURSE && shape instanceof PathShape path) {
			return coursePoint(path);
		}
		if (shape instanceof BoxShape box && !box.polygon().isEmpty()) {
			double midLat = (box.polygon().get(0).lat() + box.polygon().get(2).lat()) / 2;
			double midLng = (box.polygon().get(0).lng() + box.polygon().get(2).lng()) / 2;
			return GridEncoder.center(GridEncoder.encode(midLat, midLng));
		}
		return null;
	}

	/** PATH 시작점 — line(GeoJSON LineString, [lon, lat]) 첫 좌표. 없거나 깨졌으면 첫 스팟으로 내려간다. */
	private GridPoint coursePoint(PathShape path) {
		if (path.line() != null) {
			try {
				JsonNode first = objectMapper.readTree(path.line()).path("coordinates").path(0);
				if (first.isArray() && first.size() == 2) {
					return new GridPoint(first.get(1).asDouble(), first.get(0).asDouble());
				}
			} catch (RuntimeException e) {
				log.warn("[route] 코스 line 파싱 실패 — 첫 스팟으로 폴백: cause={}", e.getClass().getSimpleName());
			}
		}
		if (path.spots().isEmpty()) {
			return null;
		}
		return new GridPoint(path.spots().get(0).lat(), path.spots().get(0).lng());
	}

	/**
	 * 미션 지점 고유 사실 (MSG-514 §도메인 로직 4) — 코스는 스펙 합성 문장 + 소개문, 축제·팝업은 장소명 +
	 * 소개문. null 재료는 항목 생략이라 조립 규칙상 최대 2건이 자연 보장된다. 운영시간은 칸이 남는 미래
	 * 확장 후보로 미뤘다(소개문 우선 — PRD FR-6).
	 */
	private static List<String> missionDetailFacts(MissionType type, MissionResponseDto mission) {
		List<String> facts = new ArrayList<>(2);
		if (type == MissionType.COURSE) {
			String spec = courseSpecFact(mission);
			if (spec != null) {
				facts.add(spec);
			}
		} else if (mission.placeName() != null) {
			facts.add(mission.placeName());
		}
		if (mission.description() != null) {
			facts.add(mission.description());
		}
		return List.copyOf(facts);
	}

	/**
	 * 코스 스펙 합성 문장 — "총 14km, 약 5시간 30분, 난이도 보통". null 인 항목은 빼고 조립하고, 셋 다
	 * null 이면 문장 자체를 만들지 않는다(§도메인 로직 4).
	 */
	private static String courseSpecFact(MissionResponseDto mission) {
		List<String> parts = new ArrayList<>(3);
		if (mission.distanceMeters() != null) {
			parts.add("총 " + formatKm(mission.distanceMeters()) + "km");
		}
		if (mission.durationMinutes() != null) {
			parts.add("약 " + formatDuration(mission.durationMinutes()));
		}
		String difficulty = difficultyLabel(mission.difficulty());
		if (difficulty != null) {
			parts.add("난이도 " + difficulty);
		}
		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	/** km 단위 소수 첫째 자리(반올림) — 끝이 .0 이면 정수 표기 (14000m → "14", 14500m → "14.5"). */
	private static String formatKm(int meters) {
		double km = Math.round(meters / 100.0) / 10.0;
		return km == Math.floor(km) ? String.valueOf((long) km) : String.valueOf(km);
	}

	/** "{시}시간 {분}분" — 60분 미만은 분만, 정시는 시간만 (§도메인 로직 4). */
	private static String formatDuration(int minutes) {
		int hours = minutes / 60;
		int rest = minutes % 60;
		if (hours == 0) {
			return rest + "분";
		}
		return rest == 0 ? hours + "시간" : hours + "시간 " + rest + "분";
	}

	/** 두루누비 등급 라벨 — 범위 밖 값(null 포함)은 항목 생략을 뜻하는 null 이다. */
	private static String difficultyLabel(Integer difficulty) {
		if (difficulty == null) {
			return null;
		}
		return switch (difficulty) {
			case 1 -> "쉬움";
			case 2 -> "보통";
			case 3 -> "어려움";
			default -> null;
		};
	}

	/** 검색어의 지역 폴백 — 뷰포트 중심 격자의 행정동 이름. 무귀속(해상 등)이면 null 로 관심사만 남는다. */
	private String viewportCenterRegionName(ViewportBounds bounds) {
		String gridId = GridEncoder.encode(
			(bounds.swLat() + bounds.neLat()) / 2, (bounds.swLng() + bounds.neLng()) / 2);
		return gridQueryService.resolveRegionNames(List.of(gridId)).get(gridId);
	}

	private static boolean contains(ViewportBounds bounds, double lat, double lng) {
		return lat >= bounds.swLat() && lat <= bounds.neLat() && lng >= bounds.swLng() && lng <= bounds.neLng();
	}

	private static Kind missionKind(MissionType type) {
		return switch (type) {
			case EVENT -> Kind.MISSION_FESTIVAL;
			case POPUP -> Kind.MISSION_POPUP;
			case COURSE -> Kind.MISSION_COURSE;
			default -> throw new IllegalArgumentException("추천 후보 유형이 아닙니다: " + type);
		};
	}

	/** 관심사 매칭용 유형 라벨 — 모델 관심사는 한국어라 enum 이름(EVENT 등)으로는 매칭이 성립하지 않는다. */
	private static String typeLabel(MissionType type) {
		return switch (type) {
			case EVENT -> "축제";
			case POPUP -> "팝업";
			case COURSE -> "코스";
			default -> "";
		};
	}
}
