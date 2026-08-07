package com.msg.fillmap.hotzone.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.hotzone.config.HotZoneProperties;
import com.msg.fillmap.hotzone.exception.HotZoneErrorCode;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 실제 Redis(localhost:6379)를 사용하는 조회 계층 테스트 — HotScoreCommandServiceImplTest 방식.
 * 고정 Clock 을 과거 시각(2001-01-01)으로 잡아 버킷 키를 실서비스·집계 테스트(2000-01-01) 대역과 격리.
 * hotzone:top 은 고정 키라 Clock 으로 격리되지 않는다 — @BeforeEach 삭제 후 시작 (신규 격리 포인트).
 */
@DisplayName("HotZoneServiceImpl")
class HotZoneServiceImplTest {

	/** 2001-01-01T00:00:00Z — bucketId 45292. 실서비스·집계 테스트 버킷 대역과 격리된 과거 시각. */
	private static final Instant FIXED_INSTANT = Instant.parse("2001-01-01T00:00:00Z");
	private static final long BUCKET_SECONDS = 21600L;
	private static final long CURRENT_BUCKET = FIXED_INSTANT.getEpochSecond() / BUCKET_SECONDS;
	private static final String TOP_KEY = "hotzone:top";

	private static final ViewportBounds BOUNDS = new ViewportBounds(37.50, 127.00, 37.55, 127.05);
	private static final String IN_GRID_A = GridEncoder.encode(37.51, 127.01);
	private static final String IN_GRID_B = GridEncoder.encode(37.52, 127.02);
	private static final String OUT_GRID = GridEncoder.encode(38.00, 128.00);

	/** 표시명 계산용 합성 구역 (MSG-341) — IN_GRID_A 만 덮는 1×1 사각형이라 A 는 이름이 있고 B 는 없다. */
	private static final String ZONE_NAME = "m341핫구역";

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static HotZoneServiceImpl service;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		// zones 는 DB 대신 고정 스냅샷을 주입한다 — 이름 산술은 순수 함수라 Redis 조회 경로와 독립이다
		GridIndex inA = GridEncoder.decode(IN_GRID_A);
		ZoneNameResolver resolver = new ZoneNameResolver(List.of(Zone.builder()
			.zoneKey("m341-hotzone")
			.name(ZONE_NAME)
			.minGridY((int) inA.gridY())
			.maxGridY((int) inA.gridY())
			.minGridX((int) inA.gridX())
			.maxGridX((int) inA.gridX())
			.priority(0)
			.build()));
		service = new HotZoneServiceImpl(redisTemplate, new HotZoneProperties(50, 3), () -> resolver,
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	@BeforeEach
	void setUp() {
		redisTemplate.delete(TOP_KEY);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(TOP_KEY);
		for (long bucket = CURRENT_BUCKET - 8; bucket <= CURRENT_BUCKET; bucket++) {
			redisTemplate.delete("hotzone:" + bucket);
		}
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	private void record(long bucket, String gridId, double score) {
		redisTemplate.opsForZSet().add("hotzone:" + bucket, gridId, score);
	}

	@Test
	void 임계_이상_상위_격자가_핫스코어_내림차순으로_반환된다() {
		record(CURRENT_BUCKET, IN_GRID_A, 3);
		record(CURRENT_BUCKET, IN_GRID_B, 5);

		List<HotZoneView> hotZones = service.getHotZones(BOUNDS);

		assertThat(hotZones).extracting(HotZoneView::gridId).containsExactly(IN_GRID_B, IN_GRID_A);
		assertThat(hotZones).extracting(HotZoneView::score).containsExactly(5L, 3L);
	}

	@Test
	void 핫구역_항목에_구역_이름이_붙는다() {
		record(CURRENT_BUCKET, IN_GRID_A, 5);
		record(CURRENT_BUCKET, IN_GRID_B, 4);

		List<HotZoneView> hotZones = service.getHotZones(BOUNDS);

		// 합성 구역이 덮는 칸은 IN_GRID_A 하나뿐이라 A 는 이름 쌍이 붙고 구역 밖인 B 는 두 필드가 null 이다
		assertThat(hotZones).extracting(HotZoneView::zoneName).containsExactly(ZONE_NAME, null);
		assertThat(hotZones).extracting(HotZoneView::zoneCell).containsExactly("A-1", null);
	}

	@Test
	void 상위_K_안이라도_임계_미만_격자는_제외된다() {
		record(CURRENT_BUCKET, IN_GRID_A, 5);
		record(CURRENT_BUCKET, IN_GRID_B, 2);

		List<HotZoneView> hotZones = service.getHotZones(BOUNDS);

		assertThat(hotZones).extracting(HotZoneView::gridId).containsExactly(IN_GRID_A);
	}

	@Test
	void 업로드_1건_격자는_핫구역이_아니다() {
		record(CURRENT_BUCKET, IN_GRID_A, 1);

		assertThat(service.getHotZones(BOUNDS)).isEmpty();
	}

	@Test
	void 뷰포트_밖_핫구역은_제외된다() {
		record(CURRENT_BUCKET, OUT_GRID, 5);
		record(CURRENT_BUCKET, IN_GRID_A, 4);

		List<HotZoneView> hotZones = service.getHotZones(BOUNDS);

		assertThat(hotZones).extracting(HotZoneView::gridId).containsExactly(IN_GRID_A);
	}

	@Test
	void 핫구역이_하나도_없으면_빈_목록을_반환한다() {
		assertThat(service.getHotZones(BOUNDS)).isEmpty();
	}

	@Test
	void 룩백_8버킷_밖의_신호는_판정에서_제외된다() {
		record(CURRENT_BUCKET - 8, IN_GRID_A, 5);   // 9번째 과거 버킷 — 룩백은 현재 포함 8개(-7..0)

		assertThat(service.getHotZones(BOUNDS)).isEmpty();
	}

	@Test
	void 뒤집힌_뷰포트는_INVALID_VIEWPORT_에러다() {
		ViewportBounds flipped = new ViewportBounds(37.55, 127.00, 37.50, 127.05);

		assertThatThrownBy(() -> service.getHotZones(flipped))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", HotZoneErrorCode.INVALID_VIEWPORT);
	}

	@Test
	void NaN_좌표_뷰포트는_INVALID_VIEWPORT_에러다() {
		ViewportBounds nanBounds = new ViewportBounds(Double.NaN, 127.00, 37.55, 127.05);

		assertThatThrownBy(() -> service.getHotZones(nanBounds))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", HotZoneErrorCode.INVALID_VIEWPORT);
	}

	@Test
	void 무한대_좌표_뷰포트는_INVALID_VIEWPORT_에러다() {
		ViewportBounds infiniteBounds = new ViewportBounds(37.50, 127.00, Double.POSITIVE_INFINITY, 127.05);

		assertThatThrownBy(() -> service.getHotZones(infiniteBounds))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", HotZoneErrorCode.INVALID_VIEWPORT);
	}

	@Test
	void 합산_캐시에_30초_TTL이_설정된다() {
		record(CURRENT_BUCKET, IN_GRID_A, 5);

		service.getHotZones(BOUNDS);

		assertThat(redisTemplate.getExpire(TOP_KEY)).isBetween(1L, 30L);
	}

	@Test
	void 합산_캐시가_없으면_다음_조회가_재계산한다() {
		record(CURRENT_BUCKET, IN_GRID_A, 5);
		service.getHotZones(BOUNDS);
		redisTemplate.delete(TOP_KEY);

		List<HotZoneView> hotZones = service.getHotZones(BOUNDS);

		assertThat(redisTemplate.hasKey(TOP_KEY)).isTrue();
		assertThat(hotZones).extracting(HotZoneView::gridId).containsExactly(IN_GRID_A);
	}

	@Test
	void 여러_버킷에_걸친_신호는_균등_합산된다() {
		record(CURRENT_BUCKET - 1, IN_GRID_A, 2);
		record(CURRENT_BUCKET, IN_GRID_A, 2);

		List<HotZoneView> hotZones = service.getHotZones(BOUNDS);

		assertThat(hotZones).extracting(HotZoneView::score).containsExactly(4L);
	}
}
