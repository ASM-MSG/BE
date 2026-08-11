package com.msg.fillmap.hotzone.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import com.msg.fillmap.grid.repository.GridRegionNameProjection;
import com.msg.fillmap.grid.repository.GridRepository;
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

	/** 행정동 이름 (MSG-349) — 일괄 조회 스텁이 A 에만 이름을 주고 B 는 맵 miss(무귀속)로 둔다. */
	private static final String REGION_NAME = "서울특별시 성동구 성수1가제1동";

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static GridRepository gridRepository;
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
		// 행정동 이름 사전도 DB 대신 스텁이다 — 이 테스트의 축은 "몇 번 부르는가"와 "맵 miss 처리"라 실 DB 가 필요 없다.
		gridRepository = mock(GridRepository.class);
		service = new HotZoneServiceImpl(redisTemplate, new HotZoneProperties(50, 3), () -> resolver,
			gridRepository, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	@BeforeEach
	void setUp() {
		redisTemplate.delete(TOP_KEY);
		reset(gridRepository);
		// IN_GRID_A 만 라벨된 격자다 — INNER JOIN 이라 무귀속 격자는 결과 행 자체가 없다.
		given(gridRepository.findRegionNames(anyCollection())).willReturn(List.of(regionRow(IN_GRID_A, REGION_NAME)));
	}

	private static GridRegionNameProjection regionRow(String gridId, String regionName) {
		return new GridRegionNameProjection() {
			@Override
			public String getGridId() {
				return gridId;
			}

			@Override
			public String getRegionName() {
				return regionName;
			}
		};
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
	void 핫구역_항목마다_행정동_이름이_실린다() {
		record(CURRENT_BUCKET, IN_GRID_A, 5);

		List<HotZoneView> hotZones = service.getHotZones(BOUNDS);

		assertThat(hotZones).extracting(HotZoneView::regionName).containsExactly(REGION_NAME);
	}

	@Test
	void 핫구역_행정동_이름은_항목_수와_무관하게_일괄_조회_1회다() {
		record(CURRENT_BUCKET, IN_GRID_A, 5);
		record(CURRENT_BUCKET, IN_GRID_B, 4);

		assertThat(service.getHotZones(BOUNDS)).hasSize(2);

		// 항목이 몇 개든 조회는 1회 — 마커마다 단건 조회를 돌리면(N+1) 50칸에서 51회가 된다.
		verify(gridRepository, times(1)).findRegionNames(anyCollection());
	}

	@Test
	void 필터_통과_핫구역이_없으면_행정동_조회를_생략한다() {
		record(CURRENT_BUCKET, IN_GRID_A, 1);   // 최소 임계(3) 미만이라 통과 항목 0건

		assertThat(service.getHotZones(BOUNDS)).isEmpty();

		// 빈 IN 목록은 SQL 문법 오류다 — 0건이면 아예 부르지 않는다.
		verify(gridRepository, never()).findRegionNames(anyCollection());
	}

	@Test
	void 무귀속_핫구역_격자는_regionName이_null이다() {
		record(CURRENT_BUCKET, IN_GRID_A, 5);
		record(CURRENT_BUCKET, IN_GRID_B, 4);

		List<HotZoneView> hotZones = service.getHotZones(BOUNDS);

		// 이름 사전에 없는 격자(INNER JOIN 결과에 없음)는 맵 miss 로 null 이 된다 — 예외가 아니다.
		assertThat(hotZones).extracting(HotZoneView::regionName).containsExactly(REGION_NAME, null);
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
	void WGS84_범위_밖_유한_좌표_뷰포트는_INVALID_VIEWPORT_에러다() {
		// 1e308 은 유한이라 isFinite 를 통과하지만 Proj4J 경도 정규화가 사실상 끝나지 않는다 (Codex 지적)
		ViewportBounds outOfRange = new ViewportBounds(37.50, 1.0e308, 37.55, 1.0e308);

		assertThatThrownBy(() -> service.getHotZones(outOfRange))
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
