package com.msg.fillmap.hotzone.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.repository.GridRegionCodeNameProjection;
import com.msg.fillmap.grid.repository.GridRepository;
import com.msg.fillmap.hotzone.config.HotZoneProperties;
import com.msg.fillmap.hotzone.dto.HotZoneRegionAggregateResponseDto;
import com.msg.fillmap.hotzone.exception.HotZoneErrorCode;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 핫구역 행정 단위 집계 (MSG-466) — 실 Redis(localhost:6379) 위 판정 파이프라인 + 메모리 산술.
 * 행정동 라벨은 DB 대신 스텁이다(집계 산술이 축이라 실 DB 가 필요 없다). 고정 Clock 을 2002-01-01 로 잡아
 * 실서비스·다른 핫구역 테스트의 버킷 대역과 격리하고, 고정 키인 hotzone:top 은 매 테스트 앞뒤로 지운다.
 */
@DisplayName("HotZoneServiceImpl 행정 단위 집계")
class HotZoneAggregateServiceImplTest {

	/** 2002-01-01T00:00:00Z — bucketId 46752. 다른 핫구역 테스트(2001-01-01)와 겹치지 않는 과거 시각. */
	private static final Instant FIXED_INSTANT = Instant.parse("2002-01-01T00:00:00Z");
	private static final long BUCKET_SECONDS = 21600L;
	private static final long CURRENT_BUCKET = FIXED_INSTANT.getEpochSecond() / BUCKET_SECONDS;
	private static final String TOP_KEY = "hotzone:top";

	private static final ViewportBounds BOUNDS = new ViewportBounds(37.50, 127.00, 37.55, 127.05);

	// 같은 동(A·B) · 같은 구의 다른 동(C) · 다른 시도(D) · 라벨 없는 격자(E·F) 로 접두 그룹핑을 가른다.
	private static final String GRID_A = GridEncoder.encode(37.505, 127.005);
	private static final String GRID_B = GridEncoder.encode(37.510, 127.010);
	private static final String GRID_C = GridEncoder.encode(37.515, 127.015);
	private static final String GRID_D = GridEncoder.encode(37.520, 127.020);
	private static final String GRID_E = GridEncoder.encode(37.525, 127.025);
	private static final String GRID_F = GridEncoder.encode(37.530, 127.030);
	private static final String OUT_GRID = GridEncoder.encode(38.00, 128.00);

	private static final String DONG_CODE = "2623051000";
	private static final String DONG_NAME = "부산광역시 부산진구 부전2동";
	private static final String OTHER_DONG_CODE = "2623052000";
	private static final String OTHER_DONG_NAME = "부산광역시 부산진구 부전1동";
	private static final String SEOUL_DONG_CODE = "1123051000";
	private static final String SEOUL_DONG_NAME = "서울특별시 강남구 역삼1동";

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
		gridRepository = mock(GridRepository.class);
		ZoneNameResolver resolver = new ZoneNameResolver(List.of());
		service = new HotZoneServiceImpl(redisTemplate, new HotZoneProperties(50, 3), () -> resolver,
			gridRepository, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
	}

	@BeforeEach
	void setUp() {
		clearRedis();
		reset(gridRepository);
		// A·B 는 같은 동, C 는 같은 구의 다른 동, D 는 다른 시도. E·F 는 라벨이 없어 결과 행이 없다(무귀속).
		given(gridRepository.findRegionCodeNames(anyCollection())).willAnswer(invocation -> {
			Map<String, String[]> labels = Map.of(
				GRID_A, new String[] {DONG_CODE, DONG_NAME},
				GRID_B, new String[] {DONG_CODE, DONG_NAME},
				GRID_C, new String[] {OTHER_DONG_CODE, OTHER_DONG_NAME},
				GRID_D, new String[] {SEOUL_DONG_CODE, SEOUL_DONG_NAME});
			return invocation.<List<String>>getArgument(0).stream()
				.filter(labels::containsKey)
				.map(gridId -> labelRow(gridId, labels.get(gridId)[0], labels.get(gridId)[1]))
				.toList();
		});
		given(gridRepository.findRegionNames(anyCollection())).willReturn(List.of());
	}

	private static GridRegionCodeNameProjection labelRow(String gridId, String regionCode, String regionName) {
		return new GridRegionCodeNameProjection() {
			@Override
			public String getGridId() {
				return gridId;
			}

			@Override
			public String getRegionCode() {
				return regionCode;
			}

			@Override
			public String getRegionName() {
				return regionName;
			}
		};
	}

	@AfterEach
	void tearDown() {
		clearRedis();
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	private static void clearRedis() {
		redisTemplate.delete(TOP_KEY);
		for (long bucket = CURRENT_BUCKET - 8; bucket <= CURRENT_BUCKET; bucket++) {
			redisTemplate.delete("hotzone:" + bucket);
		}
	}

	private void record(String gridId, double score) {
		redisTemplate.opsForZSet().add("hotzone:" + CURRENT_BUCKET, gridId, score);
	}

	private List<HotZoneRegionAggregateResponseDto> aggregate(RegionUnit unit) {
		return service.getHotZoneAggregates(BOUNDS, unit);
	}

	// 검증: FR-HOTZONE-13
	@Test
	void 동_단위로_묶으면_행정동_코드_접두가_같은_핫_격자가_한_항목으로_센다() {
		record(GRID_A, 5);
		record(GRID_B, 4);
		record(GRID_C, 3);

		List<HotZoneRegionAggregateResponseDto> aggregates = aggregate(RegionUnit.DONG);

		assertThat(aggregates).extracting(HotZoneRegionAggregateResponseDto::regionCode)
			.containsExactly(DONG_CODE, OTHER_DONG_CODE);
		assertThat(aggregates).extracting(HotZoneRegionAggregateResponseDto::count).containsExactly(2, 1);
	}

	// 검증: FR-HOTZONE-13
	@Test
	void 구_단위_항목들을_시_접두로_다시_합치면_시_단위_집계와_개수가_같다() {
		record(GRID_A, 5);
		record(GRID_B, 4);
		record(GRID_C, 3);
		record(GRID_D, 6);

		Map<String, Integer> byGuRolledUp = aggregate(RegionUnit.SIGUNGU).stream()
			.collect(Collectors.toMap(
				item -> item.regionCode().substring(0, RegionUnit.SIDO.getCodePrefixLength()),
				HotZoneRegionAggregateResponseDto::count, Integer::sum));
		Map<String, Integer> bySido = aggregate(RegionUnit.SIDO).stream()
			.collect(Collectors.toMap(
				HotZoneRegionAggregateResponseDto::regionCode, HotZoneRegionAggregateResponseDto::count));

		assertThat(byGuRolledUp).isEqualTo(bySido);
	}

	// 검증: FR-HOTZONE-13
	@Test
	void 묶음의_이름은_단위별_토큰이다() {
		record(GRID_A, 5);

		assertThat(aggregate(RegionUnit.DONG)).singleElement()
			.extracting(HotZoneRegionAggregateResponseDto::name).isEqualTo("부전2동");
		assertThat(aggregate(RegionUnit.SIGUNGU)).singleElement()
			.extracting(HotZoneRegionAggregateResponseDto::name).isEqualTo("부산진구");
		assertThat(aggregate(RegionUnit.SIDO)).singleElement()
			.extracting(HotZoneRegionAggregateResponseDto::name).isEqualTo("부산광역시");
	}

	// 검증: FR-HOTZONE-13
	@Test
	void 대표_좌표는_묶음에_속한_핫_격자_셀_중심의_평균이다() {
		record(GRID_A, 5);
		record(GRID_B, 4);

		HotZoneRegionAggregateResponseDto item = aggregate(RegionUnit.DONG).get(0);

		GridPoint centerA = GridEncoder.center(GRID_A);
		GridPoint centerB = GridEncoder.center(GRID_B);
		assertThat(item.lat()).isCloseTo((centerA.lat() + centerB.lat()) / 2, within(1e-9));
		assertThat(item.lng()).isCloseTo((centerA.lon() + centerB.lon()) / 2, within(1e-9));
	}

	// 검증: FR-HOTZONE-13
	@Test
	void gridIds는_오름차순이고_크기가_count와_같다() {
		record(GRID_A, 5);
		record(GRID_B, 4);

		HotZoneRegionAggregateResponseDto item = aggregate(RegionUnit.DONG).get(0);

		assertThat(item.gridIds()).isSorted().hasSize(item.count());
		assertThat(item.gridIds()).containsExactlyInAnyOrder(GRID_A, GRID_B);
	}

	// 검증: FR-HOTZONE-13
	@Test
	void count는_핫_격자_수이고_핫스코어_합산이_아니다() {
		record(GRID_A, 5);
		record(GRID_B, 7);

		assertThat(aggregate(RegionUnit.DONG)).singleElement()
			.extracting(HotZoneRegionAggregateResponseDto::count).isEqualTo(2);
	}

	// 검증: FR-HOTZONE-13
	@Test
	void 항목은_regionCode_오름차순이고_무귀속_항목이_마지막이다() {
		record(GRID_E, 5);   // 무귀속
		record(GRID_A, 4);   // 26230…
		record(GRID_D, 3);   // 11230…

		assertThat(aggregate(RegionUnit.DONG)).extracting(HotZoneRegionAggregateResponseDto::regionCode)
			.containsExactly(SEOUL_DONG_CODE, DONG_CODE, null);
	}

	// 검증: FR-HOTZONE-13
	@Test
	void region_code가_없는_핫_격자는_regionCode가_null인_항목_하나로_묶인다() {
		record(GRID_E, 5);
		record(GRID_F, 4);

		assertThat(aggregate(RegionUnit.DONG)).singleElement()
			.satisfies(item -> {
				assertThat(item.regionCode()).isNull();
				assertThat(item.name()).isNull();
				assertThat(item.count()).isEqualTo(2);
			});
	}

	// 검증: FR-HOTZONE-13
	@Test
	void 집계_합은_같은_뷰포트_개별_조회_결과_수와_같다() {
		record(GRID_A, 5);
		record(GRID_B, 4);
		record(GRID_C, 3);
		record(GRID_E, 6);
		record(GRID_D, 2);   // 임계 미만 — 양쪽에서 같이 빠진다
		record(OUT_GRID, 9);   // 뷰포트 밖 — 양쪽에서 같이 빠진다

		int aggregatedTotal = aggregate(RegionUnit.SIGUNGU).stream()
			.mapToInt(HotZoneRegionAggregateResponseDto::count).sum();

		assertThat(aggregatedTotal).isEqualTo(service.getHotZones(BOUNDS).size());
	}

	// 검증: FR-HOTZONE-13
	@Test
	void 임계_미만_격자는_개별_조회처럼_집계에서도_빠진다() {
		record(GRID_A, 5);
		record(GRID_B, 2);

		assertThat(aggregate(RegionUnit.DONG)).singleElement()
			.satisfies(item -> assertThat(item.gridIds()).containsExactly(GRID_A));
	}

	// 검증: FR-HOTZONE-13
	@Test
	void 뷰포트_밖_핫_격자는_집계에서_빠진다() {
		record(OUT_GRID, 9);
		record(GRID_A, 4);

		assertThat(aggregate(RegionUnit.DONG)).singleElement()
			.satisfies(item -> assertThat(item.gridIds()).containsExactly(GRID_A));
	}

	@Test
	void 통과_격자가_없으면_행정동_조회를_생략하고_빈_목록을_준다() {
		record(GRID_A, 1);   // 최소 임계(3) 미만 — 통과 0건

		assertThat(aggregate(RegionUnit.DONG)).isEmpty();

		// 0건이면 물을 것이 없다 — 빈 IN 목록의 방언 차이도 피한다 (D2).
		verify(gridRepository, never()).findRegionCodeNames(anyCollection());
	}

	@Test
	void Redis에_버킷이_없으면_빈_배열이다() {
		// 핫스코어는 근사값이라 Redis 유실도 오류가 아니다 (FR-11) — 신호가 하나도 없으면 그냥 빈 집계다.
		assertThat(aggregate(RegionUnit.SIGUNGU)).isEmpty();
	}

	@Test
	void 단위별_span_상한을_넘는_뷰포트는_VIEWPORT_TOO_LARGE_에러다() {
		ViewportBounds wide = new ViewportBounds(35.00, 127.00, 36.50, 127.05);

		assertThatThrownBy(() -> service.getHotZoneAggregates(wide, RegionUnit.DONG))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", HotZoneErrorCode.VIEWPORT_TOO_LARGE);
		// 같은 뷰포트도 시 단위(10도)에서는 성립한다 — 상한은 단위별 차등이다.
		assertThat(service.getHotZoneAggregates(wide, RegionUnit.SIDO)).isEmpty();
	}

	@Test
	void 개별_조회는_종전대로_span_상한_없이_성립한다() {
		// 파이프라인 공통 추출 후에도 개별 조회 경로에는 상한이 없다 (기존 계약 불변, D5).
		ViewportBounds nationwide = new ViewportBounds(33.0, 124.0, 39.0, 132.0);

		assertThat(service.getHotZones(nationwide)).isEmpty();
	}
}
