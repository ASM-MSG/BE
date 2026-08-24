package com.msg.fillmap.hotzone.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridEncoder.GridRange;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.repository.GridRegionCodeNameProjection;
import com.msg.fillmap.grid.repository.GridRegionNameProjection;
import com.msg.fillmap.grid.repository.GridRepository;
import com.msg.fillmap.hotzone.config.HotZoneProperties;
import com.msg.fillmap.hotzone.dto.HotZoneRegionAggregateResponseDto;
import com.msg.fillmap.hotzone.exception.HotZoneErrorCode;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 핫구역 조회 구현 (MSG-184). 최근 8버킷(현재 포함) ZUNIONSTORE 균등 합산을 {@code hotzone:top}
 * (TTL 30s)에 캐시하고, 상위 K → 최소 임계 → 뷰포트 필터 순으로 판정한다 (MSG-233 D3·D4·D7).
 * 48h 윈도우 판정은 이 룩백이 보장한다 — 버킷 TTL(54h)은 청소 전용 (D4 역할 분리).
 */
@Service
public class HotZoneServiceImpl implements HotZoneService {

	private static final String KEY_PREFIX = "hotzone:";
	private static final String TOP_KEY = "hotzone:top";
	private static final long BUCKET_SECONDS = 21600L;   // UTC 6h 고정 버킷 (D2) — 집계와 동일 기준
	private static final int LOOKBACK_BUCKETS = 8;
	private static final long TOP_TTL_SECONDS = 30L;

	/** 개별 조회의 span 상한 자리 — 어떤 유한 span 도 넘지 못해 종전대로 상한 없이 지나간다 (MSG-466 D5). */
	private static final double NO_SPAN_LIMIT = Double.POSITIVE_INFINITY;

	/**
	 * 캐시 보장 원자 실행 — EXISTS 체크·ZUNIONSTORE·EXPIRE 를 분리하면 ZUNIONSTORE 후 단절 시
	 * TTL 없는 hotzone:top 이 영구 잔존해 stale 캐시를 계속 서빙한다 (INCREMENT_SCRIPT 와 같은 이유).
	 * 동시 재계산은 같은 소스의 같은 결과라 무해 — 락 불요 (D4). KEYS[1]=top, KEYS[2..9]=룩백 버킷.
	 */
	private static final RedisScript<Long> ENSURE_TOP_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('EXISTS', KEYS[1]) == 0 then "
			+ "redis.call('ZUNIONSTORE', KEYS[1], #KEYS - 1, unpack(KEYS, 2)) "
			+ "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
			+ "end return 1", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final HotZoneProperties properties;
	private final ZoneNameQueryService zoneNameQueryService;
	private final GridRepository gridRepository;
	private final Clock clock;

	@Autowired
	public HotZoneServiceImpl(StringRedisTemplate redisTemplate, HotZoneProperties properties,
		ZoneNameQueryService zoneNameQueryService, GridRepository gridRepository) {
		this(redisTemplate, properties, zoneNameQueryService, gridRepository, Clock.systemUTC());
	}

	/** 버킷 경계(6h) 결정적 테스트용 — 고정 Clock 주입 (HotScoreCommandServiceImpl 선례). */
	public HotZoneServiceImpl(StringRedisTemplate redisTemplate, HotZoneProperties properties,
		ZoneNameQueryService zoneNameQueryService, GridRepository gridRepository, Clock clock) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.zoneNameQueryService = zoneNameQueryService;
		this.gridRepository = gridRepository;
		this.clock = clock;
	}

	@Override
	public List<HotZoneView> getHotZones(ViewportBounds bounds) {
		// 개별 조회는 종전대로 span 상한이 없다 — 결과가 상위 K 로 이미 상한이다 (FR-HOTZONE-09 불변).
		validateBounds(bounds, NO_SPAN_LIMIT);
		List<TypedTuple<String>> passed = passedHotGrids(bounds);
		if (passed.isEmpty()) {
			return List.of();
		}
		// 필터를 통과한 격자의 행정동 이름을 일괄 조회 1회로 받는다 — 항목마다 단건 조회(N+1)를 돌리지 않는다.
		// 무귀속 격자는 결과에 없어 맵 miss(null)로 떨어진다 (MSG-349).
		Map<String, String> regionNames = gridRepository.findRegionNames(
				passed.stream().map(TypedTuple::getValue).toList()).stream()
			.collect(Collectors.toMap(GridRegionNameProjection::getGridId, GridRegionNameProjection::getRegionName));
		// 리졸버는 매핑 진입 전 1회 — 항목마다 zones 를 다시 읽지 않는다 (MSG-341 FR-8)
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		return passed.stream()
			.map(tuple -> {
				GridIndex index = GridEncoder.decode(tuple.getValue());
				ZoneCellName name = resolver.name(index.gridY(), index.gridX());
				return new HotZoneView(tuple.getValue(), (int) index.gridY(), (int) index.gridX(),
					Math.round(tuple.getScore()), name.zoneName(), name.zoneCell(),
					regionNames.get(tuple.getValue()));
			})
			.toList();
	}

	/**
	 * 행정 단위 집계 (MSG-466 D1·D3) — 개별 조회와 같은 판정 집합(passedHotGrids) 위의 메모리 산술이다.
	 * 통과 격자의 행정동 코드·이름을 벌크 조회 1회로 받고, 코드를 단위 길이로 자른 접두로 묶는다.
	 * 라벨이 없는 격자는 조회 결과에 없어 키 null 인 한 묶음으로 모여 맨 뒤에 실린다(MSG-356 NULLS LAST 와 같다).
	 */
	@Override
	public List<HotZoneRegionAggregateResponseDto> getHotZoneAggregates(ViewportBounds bounds, RegionUnit unit) {
		validateBounds(bounds, unit.getMaxSpanDeg());
		List<String> gridIds = passedHotGrids(bounds).stream().map(TypedTuple::getValue).sorted().toList();
		if (gridIds.isEmpty()) {
			return List.of();
		}
		Map<String, GridRegionCodeNameProjection> labels = gridRepository.findRegionCodeNames(gridIds).stream()
			.collect(Collectors.toMap(GridRegionCodeNameProjection::getGridId, projection -> projection));
		// HashMap 은 null 키를 받는다 — Collectors.groupingBy 는 무귀속 키에서 NPE 라 직접 모은다 (미션 집계와 동일).
		// gridIds 가 이미 오름차순이라 각 묶음의 목록도 그 순서를 그대로 물려받는다.
		Map<String, List<String>> grouped = new HashMap<>();
		for (String gridId : gridIds) {
			GridRegionCodeNameProjection label = labels.get(gridId);
			String key = label == null
				? null
				: label.getRegionCode().substring(
					0, Math.min(unit.getCodePrefixLength(), label.getRegionCode().length()));
			grouped.computeIfAbsent(key, unused -> new ArrayList<>()).add(gridId);
		}
		return grouped.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.nullsLast(Comparator.naturalOrder())))
			.map(entry -> toAggregate(entry.getKey(), entry.getValue(), labels, unit))
			.toList();
	}

	private static HotZoneRegionAggregateResponseDto toAggregate(String regionCode, List<String> gridIds,
		Map<String, GridRegionCodeNameProjection> labels, RegionUnit unit) {
		// MIN 단일화 — 같은 접두면 이름도 한 값이지만 결정적으로 하나를 고른다(MSG-356 MIN(split_part) 와 동일).
		String name = gridIds.stream()
			.map(labels::get)
			.filter(Objects::nonNull)
			.map(label -> nameToken(label.getRegionName(), unit.getNameTokenIndex()))
			.filter(Objects::nonNull)
			.min(Comparator.naturalOrder())
			.orElse(null);
		// 대표 좌표는 셀 중심의 평균이다 — grids.center_geom 을 읽지 않아도 격자 id 산술로 같은 값이 나온다(D3).
		List<GridPoint> centers = gridIds.stream().map(GridEncoder::center).toList();
		double lat = centers.stream().mapToDouble(GridPoint::lat).average().orElseThrow();
		double lng = centers.stream().mapToDouble(GridPoint::lon).average().orElseThrow();
		return new HotZoneRegionAggregateResponseDto(regionCode, name, lat, lng, gridIds.size(), gridIds);
	}

	/**
	 * 전체 경로 이름("부산광역시 부산진구 부전2동")의 tokenIndex 번째 공백 토큰 — 도감 집계의
	 * split_part(region_name, ' ', n) 및 미션 집계의 같은 이름 메서드와 동작을 글자 단위로 맞춘다(D3).
	 * 토큰이 모자라면 split_part 처럼 빈 문자열이고, 이름 자체가 없으면 null 이다.
	 * ponytail: MissionQueryServiceImpl.nameToken 의 쌍둥이(여섯 줄 복제) — Owner B 코드를 고치지 않으려는
	 * 의도적 복제다(그쪽 validateBounds 복제와 같은 이유). 네 번째 호출자가 생기면 global 유틸로 승격한다.
	 */
	private static String nameToken(String fullName, int tokenIndex) {
		if (fullName == null) {
			return null;
		}
		String[] tokens = fullName.split(" ", -1);
		return tokenIndex <= tokens.length ? tokens[tokenIndex - 1] : "";
	}

	/**
	 * 상위 K → 최소 임계 → 뷰포트 필터를 통과한 격자 (D1). 개별 조회와 집계가 이 한 경로를 공유하므로
	 * 같은 시각의 두 응답이 다른 집합을 볼 구조가 없다 — 캐시 세대도 같은 hotzone:top 하나다.
	 * 반환 순서는 핫스코어 내림차순(ZSET 역순 조회 순서)이다.
	 */
	private List<TypedTuple<String>> passedHotGrids(ViewportBounds bounds) {
		ensureTopCache();
		Set<TypedTuple<String>> top = redisTemplate.opsForZSet()
			.reverseRangeWithScores(TOP_KEY, 0, properties.topK() - 1L);
		if (top == null || top.isEmpty()) {
			return List.of();
		}
		// bbox 꼭짓점 4점을 GridEncoder(단일 진실 원천)로 정수 인덱스 범위로 환산해 비교한다 — 위경도 재계산 없음.
		GridRange range = GridEncoder.viewportRange(bounds);
		List<TypedTuple<String>> passed = new ArrayList<>();
		for (TypedTuple<String> tuple : top) {
			if (Math.round(tuple.getScore()) < properties.minScore()) {
				continue;
			}
			GridIndex index = GridEncoder.decode(tuple.getValue());
			if (index.gridY() < range.minGridY() || index.gridY() > range.maxGridY()
				|| index.gridX() < range.minGridX() || index.gridX() > range.maxGridX()) {
				continue;
			}
			passed.add(tuple);
		}
		return passed;
	}

	/** hotzone:top 부재 시 최근 8버킷(현재 버킷 포함, clock 기준)을 합산해 30s 캐시로 생성한다. */
	private void ensureTopCache() {
		long currentBucket = clock.instant().getEpochSecond() / BUCKET_SECONDS;
		List<String> keys = new ArrayList<>(LOOKBACK_BUCKETS + 1);
		keys.add(TOP_KEY);
		for (long bucket = currentBucket - LOOKBACK_BUCKETS + 1; bucket <= currentBucket; bucket++) {
			keys.add(KEY_PREFIX + bucket);
		}
		redisTemplate.execute(ENSURE_TOP_SCRIPT, keys, String.valueOf(TOP_TTL_SECONDS));
	}

	/**
	 * maxSpanDeg 는 호출 경로마다 다르다 — 개별 조회는 무상한(NO_SPAN_LIMIT)이고 행정 집계는 단위별 상한
	 * (RegionUnit.maxSpanDeg — DONG 1도·SIGUNGU 4도·SIDO 10도)이다 (MSG-466 D5). 정확히 상한값은 허용한다.
	 */
	private void validateBounds(ViewportBounds bounds, double maxSpanDeg) {
		// NaN 은 모든 비교가 false 라 뒤집힘 검사를 통과하고, 1e308 급 유한값은 Proj4J 경도 정규화
		// (반복 감산)가 double 정밀도에서 값을 못 줄여 사실상 무한 루프다 (Codex 지적) — 유한성만으론
		// 부족해 WGS84 범위 밖을 투영 전에 거른다.
		if (!isWgs84(bounds.swLat(), bounds.swLng()) || !isWgs84(bounds.neLat(), bounds.neLng())) {
			throw new ApiException(HotZoneErrorCode.INVALID_VIEWPORT);
		}
		if (bounds.swLat() > bounds.neLat() || bounds.swLng() > bounds.neLng()) {
			throw new ApiException(HotZoneErrorCode.INVALID_VIEWPORT);
		}
		if (bounds.neLat() - bounds.swLat() > maxSpanDeg || bounds.neLng() - bounds.swLng() > maxSpanDeg) {
			throw new ApiException(HotZoneErrorCode.VIEWPORT_TOO_LARGE);
		}
	}

	private static boolean isWgs84(double lat, double lng) {
		return Double.isFinite(lat) && Double.isFinite(lng)
			&& lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0;
	}
}
