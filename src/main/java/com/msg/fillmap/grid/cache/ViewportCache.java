package com.msg.fillmap.grid.cache;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.msg.fillmap.grid.GridEncoder.GridRange;
import com.msg.fillmap.grid.cache.ViewportCacheProperties.Mode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * (userId, 스냅 블록) → 그 블록 안의 내 점령 격자 전부. 페이지·커서 절단은 호출자가 메모리에서 한다.
 *
 * <p>LOCAL 은 Caffeine 의 {@code get(key, loader)} 가 키 단위 single-flight 라 콜드 스타트에 같은 블록을
 * 여러 요청이 동시에 요청해도 DB 는 한 번만 간다. REDIS 는 그런 병합이 없다 — 이 차이가 실험의 관측 대상이다.
 * Redis 장애는 캐시 없음으로 강등한다(요청은 살린다). 메트릭은 {@code /actuator/prometheus} 로 나간다:
 * {@code viewport_cache_lookups_total{result=l1_hit|l2_hit|miss}} · {@code viewport_db_loads_total}.
 */
@Slf4j
@Component
public class ViewportCache {

	/** 캐시에 담는 최소 필드. JSON 왕복이 있어 프로젝션 인터페이스를 직접 구현하지 않는다 (getter 가 속성으로 잡힌다). */
	public record Cell(String gridId, int gridY, int gridX, String regionName) {
	}

	private static final TypeReference<List<Cell>> CELLS = new TypeReference<>() { };

	private final ViewportCacheProperties props;
	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final Cache<String, List<Cell>> l1;
	private final Counter l1Hits;
	private final Counter l2Hits;
	private final Counter misses;
	private final Counter dbLoads;
	private final Counter redisErrors;

	public ViewportCache(ViewportCacheProperties props, StringRedisTemplate redis, ObjectMapper objectMapper,
		MeterRegistry registry) {
		this.props = props;
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.l1 = props.mode() == Mode.LOCAL || props.mode() == Mode.TWO_LEVEL
			? Caffeine.newBuilder().expireAfterWrite(props.ttl()).maximumSize(100_000).build()
			: null;
		this.l1Hits = registry.counter("viewport.cache.lookups", "result", "l1_hit");
		this.l2Hits = registry.counter("viewport.cache.lookups", "result", "l2_hit");
		this.misses = registry.counter("viewport.cache.lookups", "result", "miss");
		this.dbLoads = registry.counter("viewport.db.loads");
		this.redisErrors = registry.counter("viewport.cache.redis.errors");
		log.info("viewport cache mode={} ttl={} snapCells={}", props.mode(), props.ttl(), props.snapCells());
	}

	public boolean enabled() {
		return props.mode() != Mode.NONE;
	}

	/** 캐시 없는 경로도 DB 조회 횟수는 같은 카운터로 센다 — 네 전략의 비교 축이다. */
	public void recordDbLoad() {
		dbLoads.increment();
	}

	/** 요청 범위를 snapCells 배수 블록으로 바깥쪽 반올림한다. 결과는 요청 범위를 포함한다. */
	public GridRange snap(GridRange range) {
		int b = props.snapCells();
		if (b <= 1) {
			return range;
		}
		return new GridRange(
			Math.floorDiv(range.minGridY(), b) * b, Math.floorDiv(range.maxGridY(), b) * b + b - 1,
			Math.floorDiv(range.minGridX(), b) * b, Math.floorDiv(range.maxGridX(), b) * b + b - 1);
	}

	public List<Cell> get(long userId, GridRange block, Supplier<List<Cell>> loader) {
		String key = key(userId, block);
		return switch (props.mode()) {
			case LOCAL -> fromL1(key, () -> { misses.increment(); return load(loader); });
			case REDIS -> fromRedis(key, loader);
			case TWO_LEVEL -> fromL1(key, () -> fromRedis(key, loader));
			case NONE -> load(loader);
		};
	}

	/** Caffeine get 은 적중·적재를 구분해 주지 않아 적재 여부를 옆에서 표시한다. */
	private List<Cell> fromL1(String key, Supplier<List<Cell>> onMiss) {
		boolean[] loaded = {false};
		List<Cell> cells = l1.get(key, k -> { loaded[0] = true; return onMiss.get(); });
		if (!loaded[0]) {
			l1Hits.increment();
		}
		return cells;
	}

	private List<Cell> fromRedis(String key, Supplier<List<Cell>> loader) {
		try {
			String json = redis.opsForValue().get(key);
			if (json != null) {
				l2Hits.increment();
				return objectMapper.readValue(json, CELLS);
			}
		} catch (DataAccessException | JacksonException e) {
			// ponytail: Redis 가 죽으면 캐시 없음으로 강등. 회로 차단·백오프는 실험 범위 밖.
			redisErrors.increment();
			return load(loader);
		}
		misses.increment();
		List<Cell> cells = load(loader);
		try {
			redis.opsForValue().set(key, objectMapper.writeValueAsString(cells), props.ttl());
		} catch (DataAccessException | JacksonException e) {
			redisErrors.increment();
		}
		return cells;
	}

	private List<Cell> load(Supplier<List<Cell>> loader) {
		dbLoads.increment();
		return loader.get();
	}

	private static String key(long userId, GridRange r) {
		return "viewport:" + userId + ":" + r.minGridY() + ":" + r.maxGridY() + ":" + r.minGridX() + ":" + r.maxGridX();
	}
}
