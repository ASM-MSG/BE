package com.msg.fillmap.grid.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.msg.fillmap.grid.GridEncoder.GridRange;
import com.msg.fillmap.grid.cache.ViewportCache.Cell;
import com.msg.fillmap.grid.cache.ViewportCacheProperties.Mode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

class ViewportCacheTest {

	private static final GridRange BLOCK = new GridRange(0, 63, 0, 63);
	private static final List<Cell> CELLS = List.of(new Cell("1_2", 1, 2, "서울 강남구 역삼동"));

	private ViewportCache cache(Mode mode, StringRedisTemplate redis) {
		return new ViewportCache(new ViewportCacheProperties(mode, Duration.ofSeconds(30), 64),
			redis, new ObjectMapper(), new SimpleMeterRegistry());
	}

	@Test
	void 스냅은_요청_범위를_64배수_블록으로_바깥쪽_반올림한다() {
		GridRange snapped = cache(Mode.LOCAL, null).snap(new GridRange(70, 130, -5, 3));
		assertThat(snapped).isEqualTo(new GridRange(64, 191, -64, 63));
	}

	@Test
	void LOCAL은_같은_블록을_두_번_물으면_DB를_한_번만_간다() {
		ViewportCache cache = cache(Mode.LOCAL, null);
		AtomicInteger loads = new AtomicInteger();

		cache.get(7, BLOCK, () -> { loads.incrementAndGet(); return CELLS; });
		List<Cell> second = cache.get(7, BLOCK, () -> { loads.incrementAndGet(); return CELLS; });
		cache.get(8, BLOCK, () -> { loads.incrementAndGet(); return CELLS; }); // 다른 사용자는 다른 키

		assertThat(loads).hasValue(2);
		assertThat(second).isEqualTo(CELLS);
	}

	@Test
	void REDIS는_저장한_JSON을_그대로_되읽고_장애면_DB로_강등한다() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		ViewportCache cache = cache(Mode.REDIS, redis);
		AtomicInteger loads = new AtomicInteger();

		when(ops.get(anyString())).thenReturn(null);
		cache.get(7, BLOCK, () -> { loads.incrementAndGet(); return CELLS; });
		when(ops.get(anyString())).thenReturn("[{\"gridId\":\"1_2\",\"gridY\":1,\"gridX\":2,\"regionName\":\"서울 강남구 역삼동\"}]");
		List<Cell> hit = cache.get(7, BLOCK, () -> { loads.incrementAndGet(); return List.of(); });
		when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
		List<Cell> degraded = cache.get(7, BLOCK, () -> { loads.incrementAndGet(); return CELLS; });

		assertThat(loads).hasValue(2);
		assertThat(hit).isEqualTo(CELLS);
		assertThat(degraded).isEqualTo(CELLS);
	}
}
