package com.msg.fillmap.route.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto.ViewportDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto.RoutePointDto;
import com.msg.fillmap.route.exception.RouteErrorCode;
import com.msg.fillmap.route.service.RouteCandidate.Kind;
import com.msg.fillmap.route.service.RouteIntentClient.ExplainPoint;
import com.msg.fillmap.route.service.RouteIntentClient.ParsedIntent;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * AI 경로 추천 구현 (MSG-457). 처리 순서는 입력 검증 → 플래그 게이트 → 요청 제한 원자 선점 → parse(캐시)
 * → 후보 수집 → 순서 배열 → facts 조립 → explain → 응답 조립이다. 요청 제한이 검증·플래그 뒤인 것은
 * FR-ROUTE-12 의 근거가 "호출마다 외부 비용"이라 비용이 안 나가는 400·14400·14503 거부는 창을 소모하지
 * 않기 때문이다(2026-08-24 확정). 전 구간 읽기 전용 — 저장 의존이 주입되지 않아 스탬프·미션 진행이
 * 구조적으로 변하지 않는다(FR-ROUTE-09).
 *
 * 상시 빈이다 — RouteIntentClient 만 플래그 조건부라, 직접 주입하면 기본(enabled=false) 기동이 깨지고
 * 서비스·컨트롤러까지 조건부로 만들면 14503 대신 404 가 나간다(HighlightPreviewServiceImpl 선례).
 * 뷰포트 의미 검증은 parse 호출 전에 한다 — AI 까지 가서 422 로 돌아오면 의미가 다른 14502 로 샌다(§API).
 */
@Slf4j
@Service
public class RouteRecommendServiceImpl implements RouteRecommendService {

	/** 뷰포트 한 변의 위경도 span 상한(도) — 미션·행사 뷰포트 조회와 같은 값. 정확히 0.5도는 허용. */
	private static final double MAX_VIEWPORT_SPAN_DEG = 0.5;

	// WGS84 좌표계 정의역 — 범위 비교가 NaN(어떤 비교도 false)·±무한대까지 걸러낸다.
	private static final double MIN_LATITUDE_DEG = -90.0;
	private static final double MAX_LATITUDE_DEG = 90.0;
	private static final double MIN_LONGITUDE_DEG = -180.0;
	private static final double MAX_LONGITUDE_DEG = 180.0;

	/** 사용자당 최소 요청 간격 (FR-ROUTE-12) — 호출마다 외부(AI·카카오) 비용이 나간다. */
	private static final Duration RATE_LIMIT_INTERVAL = Duration.ofSeconds(10);

	/** parse 해석 캐시 (§순서 배열) — 창 안 재요청의 결과 안정화가 목적이고 유료 재호출 절감은 부수 효과다. */
	private static final Duration PARSE_CACHE_TTL = Duration.ofMinutes(10);
	private static final int PARSE_CACHE_MAX_ENTRIES = 1000;

	/** AI explain 입력 상한 (계약, 조립 시점 보장) — name·facts 각 100자 절단, facts 지점당 최대 5건. */
	private static final int MAX_AI_TEXT_LENGTH = 100;
	private static final int MAX_FACTS_PER_POINT = 5;

	/** notice 임계 (FR-ROUTE-07) — 지점 3개 이상이면 null, 0~2개면 안내가 실린다. */
	private static final int NOTICE_THRESHOLD = 3;
	private static final String EMPTY_NOTICE =
		"조건에 맞는 추천 지점을 찾지 못했어요. 지도 범위를 옮기거나 문장을 바꿔보세요.";
	private static final String INSUFFICIENT_NOTICE = "조건에 맞는 지점이 충분하지 않아 찾은 만큼만 보여드려요.";

	// ObjectProvider: RouteIntentClient 는 route.ai.enabled 일 때만 뜨는 빈이라 직접 주입하면 기동이 깨진다.
	private final ObjectProvider<RouteIntentClient> intentClientProvider;
	private final RouteCandidateCollector candidateCollector;
	private final ZoneNameQueryService zoneNameQueryService;
	private final GridQueryService gridQueryService;
	private final Clock clock;

	// ponytail: 요청 제한·parse 캐시 모두 단일 인스턴스 전제의 메모리 구조다(스펙 명시 단순화) —
	// 다중 인스턴스가 되면 Redis 로 옮긴다. 캐시는 LRU(LinkedHashMap accessOrder) + TTL 검사로 1,000건을 지킨다.
	// 요청 제한 맵은 시도한 사용자 수만큼 영구 누적된다(10만 사용자 ≈ 5MB 수준) — Redis 전환 때 TTL 과 함께 해소.
	private final ConcurrentHashMap<Long, Long> lastAttemptMillis = new ConcurrentHashMap<>();
	private final Map<ParseCacheKey, CachedParse> parseCache =
		Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<ParseCacheKey, CachedParse> eldest) {
				return size() > PARSE_CACHE_MAX_ENTRIES;
			}
		});

	/** 프로덕션 생성자 — UTC 고정 위임 (EventQueryServiceImpl 선례). 전체 생성자는 시각 제어 테스트용이다. */
	@Autowired
	public RouteRecommendServiceImpl(ObjectProvider<RouteIntentClient> intentClientProvider,
		RouteCandidateCollector candidateCollector, ZoneNameQueryService zoneNameQueryService,
		GridQueryService gridQueryService) {
		this(intentClientProvider, candidateCollector, zoneNameQueryService, gridQueryService, Clock.systemUTC());
	}

	public RouteRecommendServiceImpl(ObjectProvider<RouteIntentClient> intentClientProvider,
		RouteCandidateCollector candidateCollector, ZoneNameQueryService zoneNameQueryService,
		GridQueryService gridQueryService, Clock clock) {
		this.intentClientProvider = intentClientProvider;
		this.candidateCollector = candidateCollector;
		this.zoneNameQueryService = zoneNameQueryService;
		this.gridQueryService = gridQueryService;
		this.clock = clock;
	}

	@Override
	public RouteRecommendResponseDto recommend(long userId, RouteRecommendRequestDto request) {
		validateViewport(request.viewport());
		RouteIntentClient intentClient = intentClientProvider.getIfAvailable();
		if (intentClient == null) {
			// 플래그 꺼짐(기본)의 명시적 비활성 응답 — FE 는 404(기능 없음)와 구분해 안내한다 (비기능 운영).
			throw new ApiException(RouteErrorCode.ROUTE_DISABLED);
		}
		long startMillis = clock.millis();
		long[] spans = {-1L, -1L};	// [parse_ms, explain_ms] — 도달 전 실패는 -1 로 남는다
		try {
			claimRateLimitWindow(userId, startMillis);
			RouteRecommendResponseDto response = doRecommend(intentClient, request, spans);
			logMetrics(response.points().size() >= NOTICE_THRESHOLD ? "ok" : "insufficient",
				startMillis, spans, response.points().size());
			return response;
		} catch (ApiException e) {
			logMetrics(outcomeOf(e), startMillis, spans, 0);
			throw e;
		} catch (RuntimeException e) {
			// ApiException 밖 실패(NPE류)도 지표 없이 새면 실패율이 과소 계상된다 — 지표만 남기고 그대로 올린다.
			logMetrics("error", startMillis, spans, 0);
			throw e;
		}
	}

	private RouteRecommendResponseDto doRecommend(RouteIntentClient intentClient, RouteRecommendRequestDto request,
		long[] spans) {
		ViewportDto viewport = request.viewport();
		String text = request.text();	// DTO 컴팩트 생성자가 trim 정규화를 보장한다 — 캐시 키·AI 전송 동일 본문

		long parseStart = clock.millis();
		ParsedIntent intent = cachedParse(intentClient, text, viewport);
		spans[0] = clock.millis() - parseStart;

		ViewportBounds bounds = new ViewportBounds(
			viewport.minLat(), viewport.minLng(), viewport.maxLat(), viewport.maxLng());
		List<RouteCandidate> candidates = candidateCollector.collect(bounds, intent);
		if (candidates.isEmpty()) {
			// 빈 후보는 explain 을 부르지 않는다 (FR-ROUTE-07) — AI 계약이 points 0개를 422 로 거부하므로
			// 태우면 성공이어야 할 응답이 14502 실패로 뒤집힌다 (§도메인 로직 도입부 분기).
			return new RouteRecommendResponseDto(List.of(), EMPTY_NOTICE);
		}
		List<RouteCandidate> ordered = RouteOrderPlanner.order(candidates, intent.preferredOrder(), request.origin(),
			(viewport.minLat() + viewport.maxLat()) / 2, (viewport.minLng() + viewport.maxLng()) / 2);

		long explainStart = clock.millis();
		List<String> reasons = intentClient.explain(explainPoints(ordered));
		spans[1] = clock.millis() - explainStart;

		return assemble(ordered, reasons);
	}

	/**
	 * 요청 제한 원자 선점 (FR-ROUTE-12) — compute 하나로 직전 시도 판정과 이번 시도 기록을 처리해 동시
	 * 요청 두 개가 둘 다 통과하는 경합을 막는다. 기록은 성공 시각이 아니라 시도 시각이고 실패해도 되돌리지
	 * 않는다 — parse 까지 갔다 실패한 요청도 외부 비용은 이미 나갔다(스펙 §도메인 로직 4).
	 */
	private void claimRateLimitWindow(long userId, long nowMillis) {
		lastAttemptMillis.compute(userId, (id, last) -> {
			if (last != null && nowMillis - last < RATE_LIMIT_INTERVAL.toMillis()) {
				// compute 안에서 던지면 매핑이 직전 시도 시각 그대로 남는다 — 거부된 시도는 창을 늘리지 않는다.
				throw new ApiException(RouteErrorCode.ROUTE_RATE_LIMITED);
			}
			return nowMillis;
		});
	}

	/**
	 * parse 해석 캐시 (§순서 배열) — (trim text, viewport 좌표) 키, TTL 10분, 최대 1,000건. 캐시 창 안의
	 * 재요청은 같은 해석 → 같은 후보 조건 → 같은 순서 규칙을 타서 같은 결과가 온다(FR-ROUTE-10 이행 범위).
	 * 만료 검사만 하고 능동 청소는 없다 — LRU 상한이 크기를 지키고, 미스 경합의 중복 parse 는 수용한다.
	 */
	private ParsedIntent cachedParse(RouteIntentClient intentClient, String text, ViewportDto viewport) {
		ParseCacheKey key = new ParseCacheKey(
			text, viewport.minLat(), viewport.minLng(), viewport.maxLat(), viewport.maxLng());
		CachedParse cached = parseCache.get(key);
		if (cached != null && clock.millis() < cached.expiresAtMillis()) {
			return cached.intent();
		}
		ParsedIntent intent = intentClient.parse(text, new RouteIntentClient.Viewport(
			viewport.minLat(), viewport.minLng(), viewport.maxLat(), viewport.maxLng()));
		parseCache.put(key, new CachedParse(intent, clock.millis() + PARSE_CACHE_TTL.toMillis()));
		return intent;
	}

	/**
	 * explain 입력 조립 (§도메인 로직 3) — AI 계약 상한(name·facts 각 100자, facts 1~5개)을 조립 시점에
	 * 보장한다. 절단은 AI 로 보내는 요청에만 적용하고 클라이언트 응답의 name 은 원문 그대로다(§API 응답 표).
	 * kind 는 응답 다섯 값의 소문자다. 지점 수는 후보 선별 상한 8이라 AI 방어 상한 20의 안쪽이 자연 보장된다.
	 */
	private List<ExplainPoint> explainPoints(List<RouteCandidate> ordered) {
		List<ExplainPoint> points = new ArrayList<>();
		for (int i = 0; i < ordered.size(); i++) {
			RouteCandidate candidate = ordered.get(i);
			points.add(new ExplainPoint(
				truncate(candidate.name()),
				candidate.kind().name().toLowerCase(Locale.ROOT),
				facts(candidate, i > 0 ? ordered.get(i - 1) : null)));
		}
		return points;
	}

	/** facts 조립 순서(§도메인 로직 3): 출처(상시 1건 — 하한 1 보장) → 기간 → 관심사 일치 → 직전 거리. */
	private List<String> facts(RouteCandidate candidate, RouteCandidate previous) {
		List<String> facts = new ArrayList<>();
		facts.add(sourceFact(candidate.kind()));
		if (candidate.periodStart() != null && candidate.periodEnd() != null) {
			// 시더가 KST 자정을 전날 15:00 UTC 로 저장한다 — UTC 날짜 그대로면 8월 축제가 "07-31~"로 나간다.
			facts.add(truncate(RouteCandidateCollector.kstDate(candidate.periodStart())
				+ "~" + RouteCandidateCollector.kstDate(candidate.periodEnd()) + " 진행 중"));
		}
		if (candidate.matchedInterest() != null) {
			facts.add(truncate("관심사 '" + candidate.matchedInterest() + "' 일치"));
		}
		if (previous != null) {
			double km = RouteOrderPlanner.distanceMeters(
				previous.lat(), previous.lng(), candidate.lat(), candidate.lng()) / 1000.0;
			facts.add(String.format(Locale.ROOT, "이전 지점에서 %.1fkm", km));
		}
		// 조립 규칙상 최대 4건이라 자연 충족 — 상한 5는 규칙이 늘어나도 계약 위반(422)이 안 나게 하는 방어다.
		return facts.stream().limit(MAX_FACTS_PER_POINT).toList();
	}

	/** 출처 문장 — 어떤 지점이든 사실 문장을 최소 1건 보장하는 상시 항목 (AI 계약 facts 하한 1). */
	private static String sourceFact(Kind kind) {
		return switch (kind) {
			case MISSION_FESTIVAL -> "축제 미션 후보";
			case MISSION_POPUP -> "팝업 미션 후보";
			case MISSION_COURSE -> "코스 미션 후보";
			case EVENT -> "행사 위치";
			case PLACE -> "장소 검색 결과";
		};
	}

	/**
	 * 응답 조립 — 표시명 재료는 리졸버 요청당 1회 로드 후 지점마다 순수 계산, 행정동 이름은 일괄 1회 조회다
	 * (둘 다 N+1 봉쇄 계약, §계약 변경). reasons 는 explain 검증을 통과한 값이라 points 와 개수·순서가 같다.
	 */
	private RouteRecommendResponseDto assemble(List<RouteCandidate> ordered, List<String> reasons) {
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		Map<String, String> regionNames = gridQueryService.resolveRegionNames(
			ordered.stream().map(RouteCandidate::gridId).distinct().toList());
		List<RoutePointDto> points = new ArrayList<>();
		for (int i = 0; i < ordered.size(); i++) {
			RouteCandidate candidate = ordered.get(i);
			GridIndex index = GridEncoder.decode(candidate.gridId());
			ZoneCellName zone = resolver.name(index.gridY(), index.gridX());
			points.add(new RoutePointDto(i + 1, candidate.name(), candidate.kind().name(),
				candidate.lat(), candidate.lng(), candidate.gridId(), zone.zoneName(), zone.zoneCell(),
				regionNames.get(candidate.gridId()), reasons.get(i),
				candidate.missionId(), candidate.occurrenceId()));
		}
		return new RouteRecommendResponseDto(points,
			points.size() >= NOTICE_THRESHOLD ? null : INSUFFICIENT_NOTICE);
	}

	private static String truncate(String value) {
		return value.length() <= MAX_AI_TEXT_LENGTH ? value : value.substring(0, MAX_AI_TEXT_LENGTH);
	}

	/** 지표 로그 (비기능 운영) — 사용자 문장 원문과 AI 응답 원문은 남기지 않는다. 집계는 이 라인으로 한다. */
	private void logMetrics(String outcome, long startMillis, long[] spans, int points) {
		log.info("[route] outcome={} total_ms={} parse_ms={} explain_ms={} points={}",
			outcome, clock.millis() - startMillis, spans[0], spans[1], points);
	}

	private static String outcomeOf(ApiException e) {
		if (e.getErrorCode() == RouteErrorCode.ROUTE_RATE_LIMITED) {
			return "rate_limited";
		}
		if (e.getErrorCode() == RouteErrorCode.ROUTE_AI_UNAVAILABLE) {
			return "ai_error";
		}
		return "error";
	}

	/**
	 * 뷰포트 의미 검증 (parse 호출 전, §API) — 순서는 좌표 유효성 → 뒤집힘·넓이 0 → span 상한이다.
	 * min == max(넓이 0)도 14400 인 점이 미션·행사 검증(등호 통과)과 다르다 — AI 의 Viewport 검증(엄격,
	 * 등호도 422)과 같은 규칙을 BE 가 먼저 적용하는 것이 이 검증의 존재 이유라서다.
	 * ponytail: EventQueryServiceImpl.validateBounds 계열의 네 번째 복제 — 공통 validator 승격은
	 * 2026-09-07 멘토 라이브 코드 리뷰 전 구조 변경 금지 합의로 유예 (MSG-439 §API 1 과 같은 조건).
	 */
	private void validateViewport(ViewportDto viewport) {
		if (!isValidLat(viewport.minLat()) || !isValidLat(viewport.maxLat())
			|| !isValidLng(viewport.minLng()) || !isValidLng(viewport.maxLng())) {
			throw new ApiException(RouteErrorCode.INVALID_VIEWPORT);
		}
		if (viewport.minLat() >= viewport.maxLat() || viewport.minLng() >= viewport.maxLng()) {
			throw new ApiException(RouteErrorCode.INVALID_VIEWPORT);
		}
		if (viewport.maxLat() - viewport.minLat() > MAX_VIEWPORT_SPAN_DEG
			|| viewport.maxLng() - viewport.minLng() > MAX_VIEWPORT_SPAN_DEG) {
			throw new ApiException(RouteErrorCode.VIEWPORT_TOO_LARGE);
		}
	}

	private boolean isValidLat(double lat) {
		return lat >= MIN_LATITUDE_DEG && lat <= MAX_LATITUDE_DEG;
	}

	private boolean isValidLng(double lng) {
		return lng >= MIN_LONGITUDE_DEG && lng <= MAX_LONGITUDE_DEG;
	}

	/** 해석 캐시 키 — trim 본문과 뷰포트 네 좌표의 record 동등성이 그대로 키다 (§순서 배열). */
	private record ParseCacheKey(String text, double minLat, double minLng, double maxLat, double maxLng) {
	}

	/** 캐시 항목 — TTL 판정용 만료 시각을 함께 든다. 만료 항목은 다음 조회가 덮어쓴다. */
	private record CachedParse(ParsedIntent intent, long expiresAtMillis) {
	}
}
