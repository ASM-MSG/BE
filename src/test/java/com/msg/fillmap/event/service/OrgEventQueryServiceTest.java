package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.dto.OrgEventCityCountResponseDto;
import com.msg.fillmap.event.dto.OrgEventItemResponseDto;
import com.msg.fillmap.event.dto.OrgEventListResponseDto;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventLocationGrid;
import com.msg.fillmap.event.entity.EventLocationType;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

/**
 * 행사 운영자 콘솔의 승인 이벤트 목록 (실 PostgreSQL, MSG-501). 노출 조건·집계·정렬이 전부 서버 시각과
 * DB 데이터에 걸린 판정이라 모킹으로는 검증되지 않는다 — 고정 클럭만 주입하고 나머지는 실제 스택으로 돈다.
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg501-*)와 매 테스트 고유한 합성 시·도 이름만 쓰고 {@code @Transactional}
 * 롤백한다. 조회가 테이블 전량을 후보로 읽으므로 단언은 항상 이 테스트가 만든 회차 id 와 합성 시·도로 좁힌
 * 뒤 한다 — 시드 4건이나 주변 데이터가 있어도 결과가 흔들리지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("EventQueryService 승인 이벤트 목록 (실 PostgreSQL)")
class OrgEventQueryServiceTest {

	/** 참여형 위치의 커버 이미지 공개 주소 조립에만 쓰인다 — 시드 위치는 키가 없어 결과가 null 이다. */
	private static final AwsProperties AWS_PROPERTIES = new AwsProperties("ap-northeast-2",
		new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));

	/** 고정 서버 시각 — 종료 전 판정의 기준. UTC 저장 컬럼과 같은 축이라 존 스큐가 없다. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 합성 격자 인덱스 시퀀스 — 한 회차 안 격자 유일성 제약(uq_event_grid_per_occ)을 피한다. */
	private static final AtomicInteger 격자_시퀀스 = new AtomicInteger(900_000);

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private EventLocationGridRepository locationGridRepository;

	@Autowired
	private EventVideoRepository eventVideoRepository;

	@Autowired
	private GridQueryService gridQueryService;

	@Autowired
	private ZoneNameQueryService zoneNameQueryService;

	@Autowired
	private EventNotificationService eventNotificationService;

	private EventQueryService service() {
		return new EventQueryServiceImpl(occurrenceRepository, locationRepository, locationGridRepository,
			eventVideoRepository, gridQueryService, zoneNameQueryService, eventNotificationService,
			AWS_PROPERTIES, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	/* ---------- 픽스처 ---------- */

	private String 키(String suffix) {
		return "msg501-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** 이 테스트만의 합성 시·도 — 전체 기준 집계를 주변 데이터와 섞이지 않게 단언하기 위한 격리 수단이다. */
	private String 시도(String suffix) {
		return "시501" + suffix + UUID.randomUUID().toString().substring(0, 6);
	}

	private EventSeries 시리즈() {
		return seriesRepository.save(new EventSeries(키("series"), "테스트 시리즈"));
	}

	private EventOccurrence 회차(EventSeries series, String title, String cityName,
		LocalDateTime startsAt, LocalDateTime endsAt) {
		EventOccurrence occurrence = new EventOccurrence(series, 키("occ"));
		occurrence.update(series, title, cityName, startsAt, endsAt, 100, 102, 200, 202);
		return occurrenceRepository.save(occurrence);
	}

	/** 종료 전(예정) 회차 — 노출 조건이 논점이 아닌 테스트의 기본값이다. */
	private EventOccurrence 예정회차(EventSeries series, String title, String cityName) {
		return 회차(series, title, cityName, NOW.plusDays(3), NOW.plusDays(4));
	}

	private EventLocation 위치(EventOccurrence occurrence, String name, int displayOrder) {
		String gridId = 격자_시퀀스.getAndIncrement() + "_900000";
		EventLocation location = new EventLocation(occurrence, 키("loc"));
		location.update(occurrence, name, EventLocationType.POPUP, "11:00 ~ 20:00", displayOrder, gridId);
		locationRepository.save(location);
		locationGridRepository.save(new EventLocationGrid(location.getId(), occurrence.getId(), gridId));
		return location;
	}

	private List<Long> 회차ids(OrgEventListResponseDto response) {
		return response.events().stream().map(OrgEventItemResponseDto::occurrenceId).toList();
	}

	private List<OrgEventCityCountResponseDto> 내_시도들(OrgEventListResponseDto response, List<String> cityNames) {
		return response.cityCounts().stream()
			.filter(count -> cityNames.contains(count.cityName()))
			.toList();
	}

	@Nested
	@DisplayName("노출 조건")
	class Visibility {

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("종료 전 이벤트만 목록에 실린다 — 예정·진행 중")
		void 종료_전_이벤트만_목록에_실린다() {
			EventSeries series = 시리즈();
			String city = 시도("A");
			EventOccurrence 예정 = 회차(series, "예정 이벤트", city, NOW.plusDays(3), NOW.plusDays(4));
			EventOccurrence 진행중 = 회차(series, "진행 중 이벤트", city, NOW.minusDays(1), NOW.plusDays(1));
			회차(series, "종료된 이벤트", city, NOW.minusDays(5), NOW.minusDays(1));

			assertThat(회차ids(service().getApprovedEvents(city, null)))
				.containsExactly(진행중.getId(), 예정.getId());
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("종료 후 업로드 유예 중인 이벤트는 실리지 않는다")
		void 종료_후_유예_중인_이벤트는_실리지_않는다() {
			EventSeries series = 시리즈();
			String city = 시도("B");
			회차(series, "유예 중 이벤트", city, NOW.minusDays(5), NOW.minusDays(1));

			OrgEventListResponseDto response = service().getApprovedEvents(city, null);

			assertThat(response.events()).isEmpty();
			assertThat(내_시도들(response, List.of(city))).isEmpty();
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("아카이브된 이벤트는 실리지 않는다")
		void 아카이브된_이벤트는_실리지_않는다() {
			EventSeries series = 시리즈();
			String city = 시도("C");
			회차(series, "아카이브 이벤트", city, NOW.minusDays(200), NOW.minusDays(190));

			assertThat(service().getApprovedEvents(city, null).events()).isEmpty();
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("노출 시작 전 예정 이벤트도 콘솔 목록에는 실린다 — 유저 조회의 노출 판정 미적용")
		void 노출_시작_전_예정_이벤트도_콘솔_목록에는_실린다() {
			EventSeries series = 시리즈();
			String city = 시도("D");
			EventOccurrence 노출전 = 회차(series, "먼 훗날 이벤트", city, NOW.plusDays(60), NOW.plusDays(61));

			assertThat(노출전.getVisibleFrom()).isAfter(NOW);
			assertThat(회차ids(service().getApprovedEvents(city, null))).containsExactly(노출전.getId());
		}
	}

	@Nested
	@DisplayName("필터와 검색")
	class FilterAndSearch {

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("시·도 필터를 주면 그 시·도 이벤트만 온다")
		void 시도_필터를_주면_그_시도_이벤트만_온다() {
			EventSeries series = 시리즈();
			String 대상 = 시도("E");
			String 이웃 = 시도("F");
			EventOccurrence 대상이벤트 = 예정회차(series, "대상 이벤트", 대상);
			예정회차(series, "이웃 이벤트", 이웃);

			assertThat(회차ids(service().getApprovedEvents(대상, null))).containsExactly(대상이벤트.getId());
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("없는 시·도 값이면 에러가 아니라 빈 목록이다")
		void 없는_시도_값이면_빈_목록이_온다() {
			EventSeries series = 시리즈();
			String 없는시도 = 시도("G");
			예정회차(series, "다른 시도 이벤트", 시도("g"));

			OrgEventListResponseDto response = service().getApprovedEvents(없는시도, null);

			assertThat(response.events()).isEmpty();
			assertThat(내_시도들(response, List.of(없는시도))).isEmpty();
			assertThat(response.totalCount()).isPositive();
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("이름 검색은 부분 일치이고 대소문자를 무시한다")
		void 이름_검색은_부분_일치_대소문자_무시다() {
			EventSeries series = 시리즈();
			String city = 시도("H");
			EventOccurrence 대상 = 예정회차(series, "부산 Film Festival", city);
			예정회차(series, "부산 불꽃축제", city);

			assertThat(회차ids(service().getApprovedEvents(city, "film"))).containsExactly(대상.getId());
			assertThat(회차ids(service().getApprovedEvents(city, "FILM"))).containsExactly(대상.getId());
			assertThat(회차ids(service().getApprovedEvents(city, "불꽃"))).hasSize(1);
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("시·도 필터와 이름 검색은 함께 적용된다")
		void 시도_필터와_이름_검색은_함께_적용된다() {
			EventSeries series = 시리즈();
			String 대상 = 시도("I");
			String 이웃 = 시도("J");
			EventOccurrence 대상이벤트 = 예정회차(series, "공통 이름 이벤트", 대상);
			예정회차(series, "공통 이름 이벤트", 이웃);
			예정회차(series, "다른 이름 이벤트", 대상);

			assertThat(회차ids(service().getApprovedEvents(대상, "공통"))).containsExactly(대상이벤트.getId());
		}
	}

	@Nested
	@DisplayName("건수와 정렬")
	class CountAndOrder {

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("시·도별 건수는 필터와 검색어에 흔들리지 않는다 — 칩은 내비게이션이다")
		void 시도별_건수는_필터와_검색어에_흔들리지_않는다() {
			EventSeries series = 시리즈();
			String city = 시도("K");
			예정회차(series, "검색되는 이벤트", city);
			예정회차(series, "안 걸리는 이벤트", city);

			List<OrgEventCityCountResponseDto> 전체 =
				내_시도들(service().getApprovedEvents(null, null), List.of(city));
			List<OrgEventCityCountResponseDto> 검색중 =
				내_시도들(service().getApprovedEvents(city, "검색되는"), List.of(city));

			assertThat(전체).containsExactly(new OrgEventCityCountResponseDto(city, 2));
			assertThat(검색중).isEqualTo(전체);
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("필터된 요청에서도 totalCount 는 전체 건수다")
		void 필터된_요청에서도_totalCount는_전체_건수다() {
			EventSeries series = 시리즈();
			String city = 시도("L");
			예정회차(series, "필터 대상 이벤트", city);
			예정회차(series, "이웃 이벤트", 시도("M"));

			OrgEventListResponseDto 전체 = service().getApprovedEvents(null, null);
			OrgEventListResponseDto 필터됨 = service().getApprovedEvents(city, null);

			assertThat(필터됨.events()).hasSize(1);
			assertThat(필터됨.totalCount()).isEqualTo(전체.totalCount());
			assertThat(필터됨.totalCount()).isGreaterThan(필터됨.events().size());
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("시·도별 건수는 건수 내림차순, 동수는 이름 오름차순이다")
		void 시도별_건수는_건수_내림차순_동수는_이름_오름차순이다() {
			EventSeries series = 시리즈();
			String prefix = UUID.randomUUID().toString().substring(0, 6);
			String 가 = "시501" + prefix + "가";
			String 나 = "시501" + prefix + "나";
			String 다 = "시501" + prefix + "다";
			예정회차(series, "이벤트", 가);
			예정회차(series, "이벤트", 가);
			예정회차(series, "이벤트", 나);
			예정회차(series, "이벤트", 나);
			예정회차(series, "이벤트", 다);
			예정회차(series, "이벤트", 다);
			예정회차(series, "이벤트", 다);

			List<OrgEventCityCountResponseDto> counts =
				내_시도들(service().getApprovedEvents(null, null), List.of(가, 나, 다));

			assertThat(counts).extracting(OrgEventCityCountResponseDto::cityName,
					OrgEventCityCountResponseDto::count)
				.containsExactly(tuple(다, 3), tuple(가, 2), tuple(나, 2));
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("목록은 시작일 오름차순이다 — 가까운 이벤트 먼저")
		void 목록은_시작일_오름차순이다() {
			EventSeries series = 시리즈();
			String city = 시도("N");
			EventOccurrence 늦게 = 회차(series, "늦은 이벤트", city, NOW.plusDays(10), NOW.plusDays(11));
			EventOccurrence 빨리 = 회차(series, "이른 이벤트", city, NOW.plusDays(1), NOW.plusDays(2));
			EventOccurrence 중간 = 회차(series, "중간 이벤트", city, NOW.plusDays(5), NOW.plusDays(6));

			assertThat(회차ids(service().getApprovedEvents(city, null)))
				.containsExactly(빨리.getId(), 중간.getId(), 늦게.getId());
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("시작 시각이 같으면 occurrenceId 오름차순이다 — 순서 비결정 방지")
		void 시작_시각이_같으면_occurrenceId_오름차순이다() {
			EventSeries series = 시리즈();
			String city = 시도("O");
			EventOccurrence 먼저 = 회차(series, "동시각 A", city, NOW.plusDays(3), NOW.plusDays(4));
			EventOccurrence 나중 = 회차(series, "동시각 B", city, NOW.plusDays(3), NOW.plusDays(5));

			assertThat(회차ids(service().getApprovedEvents(city, null)))
				.containsExactly(먼저.getId(), 나중.getId());
			assertThat(먼저.getId()).isLessThan(나중.getId());
		}
	}

	@Nested
	@DisplayName("항목 조립")
	class Item {

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("장소 라벨은 표시 순서가 가장 앞선 위치의 name 저장값 그대로다")
		void 장소_라벨은_첫_위치의_name_저장값_그대로다() {
			EventSeries series = 시리즈();
			String city = 시도("P");
			EventOccurrence 회차 = 예정회차(series, "장소 라벨 이벤트", city);
			위치(회차, "나중 장소", 2);
			위치(회차, "영화의전당", 0);

			assertThat(service().getApprovedEvents(city, null).events())
				.extracting(OrgEventItemResponseDto::placeLabel)
				.containsExactly("영화의전당");
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("display_order 가 같으면 id 가 작은 위치의 이름이다 — DEFAULT 0 동순위")
		void display_order가_같으면_id가_작은_위치의_이름이다() {
			EventSeries series = 시리즈();
			String city = 시도("Q");
			EventOccurrence 회차 = 예정회차(series, "동순위 이벤트", city);
			EventLocation 먼저 = 위치(회차, "먼저 만든 장소", 0);
			EventLocation 나중 = 위치(회차, "나중 만든 장소", 0);

			assertThat(먼저.getId()).isLessThan(나중.getId());
			assertThat(service().getApprovedEvents(city, null).events())
				.extracting(OrgEventItemResponseDto::placeLabel)
				.containsExactly("먼저 만든 장소");
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("위치가 없는 회차는 장소 라벨이 null 이다")
		void 위치가_없는_회차는_장소_라벨이_null이다() {
			EventSeries series = 시리즈();
			String city = 시도("R");
			예정회차(series, "위치 없는 이벤트", city);

			assertThat(service().getApprovedEvents(city, null).events())
				.extracting(OrgEventItemResponseDto::placeLabel)
				.containsExactly((String) null);
		}

		// 검증: FR-EVENT-16
		@Test
		@DisplayName("이름과 기간과 시·도가 회차 저장값 그대로 실린다")
		void 이름과_기간과_시도가_회차_저장값_그대로_실린다() {
			EventSeries series = 시리즈();
			String city = 시도("S");
			EventOccurrence 회차 = 회차(series, "부산국제영화제", city, NOW.plusDays(3), NOW.plusDays(9));

			assertThat(service().getApprovedEvents(city, null).events())
				.extracting(OrgEventItemResponseDto::occurrenceId, OrgEventItemResponseDto::name,
					OrgEventItemResponseDto::cityName, OrgEventItemResponseDto::startsAt,
					OrgEventItemResponseDto::endsAt)
				.containsExactly(tuple(회차.getId(), "부산국제영화제", city, NOW.plusDays(3), NOW.plusDays(9)));
		}
	}
}
