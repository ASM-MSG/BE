package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.route.service.TmapWalkClient.Coordinate;
import com.msg.fillmap.route.service.TmapWalkClient.WalkPath;

/**
 * 실제 Redis(localhost:6379)를 사용하는 좌표쌍 캐시 테스트 (HotScoreCommandServiceImplTest 방식).
 * 키는 테스트 전용 좌표로 만들어 실서비스 키와 겹치지 않고 tearDown 에서 지운다.
 */
@DisplayName("RouteWalkSegmentCache — 좌표쌍 보행 경로 캐시")
class RouteWalkSegmentCacheTest {

	private static final double START_LAT = 35.1587;
	private static final double START_LNG = 129.1604;
	private static final double END_LAT = 35.1631;
	private static final double END_LNG = 129.1635;
	/** Double.toString 정규형 키 — Java·JSON 의 double 왕복이 값을 보존해 같은 세그먼트는 같은 키다. */
	private static final String EXPECTED_KEY = "route:walk:seg:35.1587:129.1604:35.1631:129.1635";
	private static final WalkPath 보행_경로 = new WalkPath(
		List.of(new Coordinate(35.1587, 129.1604), new Coordinate(35.1631, 129.1635)), 742);

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static RouteWalkSegmentCache cache;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		cache = new RouteWalkSegmentCache(redisTemplate, new ObjectMapper());
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(EXPECTED_KEY);
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	// 검증: FR-ROUTE-16, NFR-OPS-09
	@Test
	void 저장한_좌표쌍을_같은_좌표로_다시_조회하면_경로가_그대로_온다() {
		cache.put(START_LAT, START_LNG, END_LAT, END_LNG, 보행_경로);

		WalkPath cached = cache.get(START_LAT, START_LNG, END_LAT, END_LNG);

		assertThat(cached).isEqualTo(보행_경로);
		// 키 포맷 고정 — Double.toString 정규형이 바뀌면 배포 시점에 기존 캐시가 통째로 미스가 된다.
		assertThat(redisTemplate.hasKey(EXPECTED_KEY)).isTrue();
	}

	// 검증: NFR-OPS-09
	@Test
	void 캐시_키에는_24시간_TTL이_걸려_보관과_사용이_함께_묶인다() {
		cache.put(START_LAT, START_LNG, END_LAT, END_LNG, 보행_경로);

		Long expireSeconds = redisTemplate.getExpire(EXPECTED_KEY);

		assertThat(expireSeconds).isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
		assertThat(cache.get(START_LAT, START_LNG, END_LAT, END_LNG)).isEqualTo(보행_경로);
	}

	@Test
	void 저장하지_않은_좌표쌍_조회는_null_이다() {
		assertThat(cache.get(START_LAT, START_LNG, END_LAT, END_LNG)).isNull();
	}

	// 검증: NFR-OPS-09, FR-ROUTE-16
	@Test
	void Redis_오류는_조회_미스와_저장_무시로_삼켜진다() {
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
		deadFactory.afterPropertiesSet();
		StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
		deadTemplate.afterPropertiesSet();
		RouteWalkSegmentCache deadCache = new RouteWalkSegmentCache(deadTemplate, new ObjectMapper());

		// 조회 오류 = 미스 취급 (호출 진행 재료), 저장 오류 = 무시 — 캐시는 없어도 안전한 절감 장치다.
		assertThat(deadCache.get(START_LAT, START_LNG, END_LAT, END_LNG)).isNull();
		assertThatCode(() -> deadCache.put(START_LAT, START_LNG, END_LAT, END_LNG, 보행_경로))
			.doesNotThrowAnyException();

		deadFactory.destroy();
	}

	@Test
	void 값이_역직렬화되지_않으면_미스로_취급한다() {
		redisTemplate.opsForValue().set(EXPECTED_KEY, "json 아님");

		assertThat(cache.get(START_LAT, START_LNG, END_LAT, END_LNG)).isNull();
	}
}
