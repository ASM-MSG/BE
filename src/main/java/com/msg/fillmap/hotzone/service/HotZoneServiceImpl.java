package com.msg.fillmap.hotzone.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridRange;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.hotzone.config.HotZoneProperties;
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
	private final Clock clock;

	@Autowired
	public HotZoneServiceImpl(StringRedisTemplate redisTemplate, HotZoneProperties properties,
		ZoneNameQueryService zoneNameQueryService) {
		this(redisTemplate, properties, zoneNameQueryService, Clock.systemUTC());
	}

	/** 버킷 경계(6h) 결정적 테스트용 — 고정 Clock 주입 (HotScoreCommandServiceImpl 선례). */
	public HotZoneServiceImpl(StringRedisTemplate redisTemplate, HotZoneProperties properties,
		ZoneNameQueryService zoneNameQueryService, Clock clock) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.zoneNameQueryService = zoneNameQueryService;
		this.clock = clock;
	}

	@Override
	public List<HotZoneView> getHotZones(ViewportBounds bounds) {
		validateBounds(bounds);
		ensureTopCache();
		Set<TypedTuple<String>> top = redisTemplate.opsForZSet()
			.reverseRangeWithScores(TOP_KEY, 0, properties.topK() - 1L);
		if (top == null || top.isEmpty()) {
			return List.of();
		}
		// bbox 꼭짓점 4점을 GridEncoder(단일 진실 원천)로 정수 인덱스 범위로 환산해 비교한다 — 위경도 재계산 없음.
		GridRange range = GridEncoder.viewportRange(bounds);
		// 리졸버는 루프 진입 전 1회 — 항목마다 zones 를 다시 읽지 않는다 (MSG-341 FR-8)
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		List<HotZoneView> hotZones = new ArrayList<>();
		for (TypedTuple<String> tuple : top) {
			long score = Math.round(tuple.getScore());
			if (score < properties.minScore()) {
				continue;
			}
			GridIndex index = GridEncoder.decode(tuple.getValue());
			if (index.gridY() < range.minGridY() || index.gridY() > range.maxGridY()
				|| index.gridX() < range.minGridX() || index.gridX() > range.maxGridX()) {
				continue;
			}
			ZoneCellName name = resolver.name(index.gridY(), index.gridX());
			hotZones.add(new HotZoneView(tuple.getValue(), (int) index.gridY(), (int) index.gridX(), score,
				name.zoneName(), name.zoneCell()));
		}
		return hotZones;
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

	private void validateBounds(ViewportBounds bounds) {
		// NaN 은 모든 비교가 false 라 뒤집힘 검사를 통과하고 GridEncoder 환산에서 인덱스 0 으로 떨어진다
		// — 비유한 값(NaN·Infinity)은 뒤집힘 검사보다 먼저 거른다.
		if (!Double.isFinite(bounds.swLat()) || !Double.isFinite(bounds.swLng())
			|| !Double.isFinite(bounds.neLat()) || !Double.isFinite(bounds.neLng())) {
			throw new ApiException(HotZoneErrorCode.INVALID_VIEWPORT);
		}
		if (bounds.swLat() > bounds.neLat() || bounds.swLng() > bounds.neLng()) {
			throw new ApiException(HotZoneErrorCode.INVALID_VIEWPORT);
		}
	}
}
