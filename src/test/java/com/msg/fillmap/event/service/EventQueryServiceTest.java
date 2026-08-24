package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.dto.EventLocationResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceChipResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceDetailResponseDto;
import com.msg.fillmap.event.dto.GridEventLocationResponseDto;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventLocationGrid;
import com.msg.fillmap.event.entity.EventLocationType;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventNotificationSubscriptionRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.repository.GridRepository;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.region.RegionTestFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.repository.ZoneRepository;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

/**
 * 행사 조회 서비스 (실 PostgreSQL, MSG-439). 상태·노출·정렬이 전부 서버 시각과 DB 데이터에 걸린 판정이라
 * 모킹으로는 검증되지 않는다 — 고정 클럭만 주입하고 나머지는 실제 스택으로 돈다.
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg439-*)와 서해 먼바다 격자만 쓰고 @Transactional 롤백한다. 목록 조회는
 * 테이블 전량을 후보로 읽으므로 단언은 항상 이 테스트가 만든 id 로 좁힌 뒤 한다 — 주변 데이터가 있어도
 * 결과가 흔들리지 않는다. zones 는 표시명 단언에 필요한 사각형을 이 테스트가 직접 시드하고 롤백으로 지운다.
 */
@SpringBootTest
@Transactional
@DisplayName("EventQueryService 행사 조회 (실 PostgreSQL)")
class EventQueryServiceTest {

	/** 고정 서버 시각 — 상태·노출 판정의 기준. UTC 저장 컬럼과 같은 축이라 존 스큐가 없다. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 서해 먼바다 기준 격자 — 육상 실데이터와 겹치지 않고 행정동 판정도 없다(regionName null 이 정상). */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.0));

	/** 중앙자오선 곡률 반례 (MSG-398 D3 실측) — 남쪽 변 중간점이 두 귀퉁이보다 한 행 아래다. */
	private static final double 곡률_LAT = 33.444927;
	private static final double 곡률_서쪽_LON = 127.279553;
	private static final double 곡률_중간_LON = 127.441772;
	private static final double 곡률_동쪽_LON = 127.603991;

	/**
	 * 행정동 이름 검증용 합성 행정동 (GridQueryServiceIntegrationTest 선례). regions 시딩은 기본 off 라
	 * CI 는 regions 가 빈 상태다 — 실 행정동 좌표에 기대면 로컬만 통과하고 CI 에서 깨진다(MSG-349).
	 * 실존하지 않는 sido 999 대역 코드라 실데이터와 충돌하지 않고, 롤백으로 사라진다.
	 */
	private static final String REGION_CODE = "9996000439";
	private static final String REGION_NAME = "합성시 합성구 합성439동";

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
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ZoneRepository zoneRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private GridRepository gridRepository;

	@Autowired
	private GridQueryService gridQueryService;

	@Autowired
	private ZoneNameQueryService zoneNameQueryService;

	@Autowired
	private EventNotificationService eventNotificationService;

	@Autowired
	private EventNotificationSubscriptionRepository subscriptionRepository;

	private EventQueryService service() {
		return service(NOW);
	}

	private EventQueryService service(LocalDateTime now) {
		return new EventQueryServiceImpl(occurrenceRepository, locationRepository, locationGridRepository,
			eventVideoRepository, gridQueryService, zoneNameQueryService, eventNotificationService,
			Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	/* ---------- 픽스처 ---------- */

	private String 키(String suffix) {
		return "msg439-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private String 격자(long dy, long dx) {
		return (바다.gridY() + dy) + "_" + (바다.gridX() + dx);
	}

	private EventSeries 시리즈() {
		return seriesRepository.save(new EventSeries(키("series"), "테스트 시리즈"));
	}

	private EventOccurrence 회차(EventSeries series, String title, String cityName,
		LocalDateTime startsAt, LocalDateTime endsAt) {
		return 회차(series, title, cityName, startsAt, endsAt, 바다.gridY(), 바다.gridY() + 2,
			바다.gridX(), 바다.gridX() + 2);
	}

	private EventOccurrence 회차(EventSeries series, String title, String cityName, LocalDateTime startsAt,
		LocalDateTime endsAt, long minGridY, long maxGridY, long minGridX, long maxGridX) {
		EventOccurrence occurrence = new EventOccurrence(series, 키("occ"));
		occurrence.update(series, title, cityName, startsAt, endsAt,
			(int) minGridY, (int) maxGridY, (int) minGridX, (int) maxGridX);
		return occurrenceRepository.save(occurrence);
	}

	private EventLocation 위치(EventOccurrence occurrence, String name, int displayOrder, String... gridIds) {
		EventLocation location = new EventLocation(occurrence, 키("loc"));
		location.update(occurrence, name, EventLocationType.POPUP, "11:00 ~ 20:00", displayOrder, gridIds[0]);
		locationRepository.save(location);
		for (String gridId : gridIds) {
			locationGridRepository.save(new EventLocationGrid(location.getId(), occurrence.getId(), gridId));
		}
		return location;
	}

	/** 전역 노출 게이트(ACTIVE·PUBLIC·READY)를 통과하는 행사 영상 하나. */
	private void 노출영상(EventLocation location) {
		Video video = 영상(location.getRepresentativeGridId(), Visibility.PUBLIC);
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg");
		연결(video, location);
	}

	/** 게이트 밖 영상 3종 — 비공개·인코딩 미완·삭제. 피드(MSG-440)에도 안 나오므로 카운트에서도 빠져야 한다. */
	private void 비공개영상(EventLocation location) {
		Video video = 영상(location.getRepresentativeGridId(), Visibility.PRIVATE);
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg");
		연결(video, location);
	}

	private void 인코딩중영상(EventLocation location) {
		연결(영상(location.getRepresentativeGridId(), Visibility.PUBLIC), location);
	}

	private void 삭제영상(EventLocation location) {
		Video video = 영상(location.getRepresentativeGridId(), Visibility.PUBLIC);
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg");
		video.markDeleted();
		연결(video, location);
	}

	private Long 사용자() {
		return userRepository.save(User.createLocalUser(
			"msg439-" + UUID.randomUUID() + "@example.com", "hash", "테스터")).getId();
	}

	private Video 영상(String gridId, Visibility visibility) {
		Long userId = 사용자();
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		return Video.create(userId, gridId, "videos/original/" + UUID.randomUUID() + ".mp4",
			GeoSupport.toPoint(center.lat(), center.lon()), (short) 10, NOW, visibility);
	}

	private void 연결(Video video, EventLocation location) {
		videoRepository.save(video);
		eventVideoRepository.save(new EventVideo(video, location, location.getOccurrence().getId()));
	}

	/** 격자 사각형을 덮는 구역 하나 — zones 는 로컬에서 비어 있어 표시명 단언에는 자체 시드가 필요하다. */
	private void 구역(String gridId) {
		GridIndex index = GridEncoder.decode(gridId);
		zoneRepository.save(Zone.builder()
			.zoneKey(키("zone"))
			.name("테스트구역")
			.minGridY((int) index.gridY())
			.maxGridY((int) index.gridY())
			.minGridX((int) index.gridX())
			.maxGridX((int) index.gridX())
			.priority(0)
			.build());
	}

	/** 격자 한 칸을 덮는 합성 행정동 — 실 regions 데이터 유무와 무관하게 regionName 기대값을 고정한다. */
	private void 합성행정동(long dy, long dx) {
		regionRepository.upsert(REGION_CODE, REGION_NAME, REGION_CODE.substring(0, 5),
			RegionTestFixtures.cellBlockPolygonJson(바다.gridY() + dy, 바다.gridY() + dy + 1,
				바다.gridX() + dx, 바다.gridX() + dx + 1),
			RegionTestFixtures.CELL_AREA_M2);
	}

	private List<Long> 회차ids(List<EventOccurrenceChipResponseDto> chips, List<EventOccurrence> mine) {
		List<Long> ids = mine.stream().map(EventOccurrence::getId).toList();
		return chips.stream().map(EventOccurrenceChipResponseDto::occurrenceId).filter(ids::contains).toList();
	}

	@Nested
	@DisplayName("뷰포트 행사 목록 (API 1)")
	class Viewport {

		private ViewportBounds 바다뷰포트() {
			return new ViewportBounds(33.99, 124.99, 34.01, 125.01);
		}

		@Test
		@DisplayName("한 뷰포트에 여러 시의 행사가 잡히면 시 이름과 시작일 순서로 정렬된다")
		void 한_뷰포트에_여러_시의_행사가_잡히면_시_이름과_시작일_순서로_정렬된다() {
			// 검증: FR-EVENT-01
			EventSeries series = 시리즈();
			EventOccurrence 서울_늦게 = 회차(series, "서울 늦은 행사", "서울", NOW.plusDays(5), NOW.plusDays(6));
			EventOccurrence 부산 = 회차(series, "부산 행사", "부산", NOW.plusDays(1), NOW.plusDays(2));
			EventOccurrence 서울_빨리 = 회차(series, "서울 이른 행사", "서울", NOW.plusDays(2), NOW.plusDays(3));

			List<EventOccurrenceChipResponseDto> chips = service().getOccurrencesInViewport(바다뷰포트());

			assertThat(회차ids(chips, List.of(서울_늦게, 부산, 서울_빨리)))
				.containsExactly(부산.getId(), 서울_빨리.getId(), 서울_늦게.getId());
		}

		@Test
		@DisplayName("주 정렬 키가 동률이면 회차 id 오름차순 타이브레이커로 결정적이다")
		void 주_정렬_키가_동률이면_id_타이브레이커로_결정적이다() {
			EventSeries series = 시리즈();
			EventOccurrence 먼저 = 회차(series, "동시 행사 A", "부산", NOW.plusDays(1), NOW.plusDays(2));
			EventOccurrence 나중 = 회차(series, "동시 행사 B", "부산", NOW.plusDays(1), NOW.plusDays(2));

			List<EventOccurrenceChipResponseDto> chips = service().getOccurrencesInViewport(바다뷰포트());

			assertThat(회차ids(chips, List.of(먼저, 나중))).containsExactly(먼저.getId(), 나중.getId());
		}

		@Test
		@DisplayName("뷰포트와 겹치는 행사가 없으면 빈 목록이 반환된다")
		void 뷰포트와_겹치는_행사가_없으면_빈_목록이_반환된다() {
			EventOccurrence 먼_행사 = 회차(시리즈(), "먼 행사", "부산", NOW.plusDays(1), NOW.plusDays(2));

			List<EventOccurrenceChipResponseDto> chips = service()
				.getOccurrencesInViewport(new ViewportBounds(36.00, 126.00, 36.05, 126.05));

			assertThat(회차ids(chips, List.of(먼_행사))).isEmpty();
		}

		@Test
		@DisplayName("중앙자오선을 품는 뷰포트에서 남쪽 경계의 행사가 누락되지 않는다 (투영 보정 회귀)")
		void 중앙자오선을_품는_뷰포트에서_남쪽_경계의_행사가_누락되지_않는다() {
			GridIndex 서쪽 = GridEncoder.decode(GridEncoder.encode(곡률_LAT, 곡률_서쪽_LON));
			GridIndex 중간 = GridEncoder.decode(GridEncoder.encode(곡률_LAT, 곡률_중간_LON));
			GridIndex 동쪽 = GridEncoder.decode(GridEncoder.encode(곡률_LAT, 곡률_동쪽_LON));
			assertThat(Math.min(서쪽.gridY(), 동쪽.gridY()))
				.as("전제: 중간점이 양끝보다 남쪽 행이어야 곡률 반례가 성립한다 (MSG-398 D3 실측)")
				.isGreaterThan(중간.gridY());

			// 노출 영역이 중간점 행 한 칸 — 꼭짓점 네 점의 min/max 만으로는 이 행이 뷰포트 밖이고,
			// 사방 1칸 보정이 있어야 닿는다.
			EventOccurrence 남쪽경계 = 회차(시리즈(), "남쪽 경계 행사", "제주", NOW.plusDays(1), NOW.plusDays(2),
				중간.gridY(), 중간.gridY(), 중간.gridX(), 중간.gridX());

			List<EventOccurrenceChipResponseDto> chips = service().getOccurrencesInViewport(
				new ViewportBounds(곡률_LAT, 곡률_서쪽_LON, 곡률_LAT + 0.05, 곡률_동쪽_LON));

			assertThat(회차ids(chips, List.of(남쪽경계))).containsExactly(남쪽경계.getId());
		}

		@Test
		@DisplayName("노출 시작 전인 예정 행사는 뷰포트 목록에 담기지 않는다")
		void 노출_시작_전인_예정_행사는_뷰포트_목록에_담기지_않는다() {
			// visibleFrom = startsAt - 14일이라 15일 뒤 시작은 아직 노출 전이다.
			EventOccurrence 미노출 = 회차(시리즈(), "미노출 행사", "부산", NOW.plusDays(15), NOW.plusDays(16));
			EventOccurrence 노출 = 회차(시리즈(), "노출 행사", "부산", NOW.plusDays(13), NOW.plusDays(14));

			List<EventOccurrenceChipResponseDto> chips = service().getOccurrencesInViewport(바다뷰포트());

			assertThat(회차ids(chips, List.of(미노출, 노출))).containsExactly(노출.getId());
		}

		@Test
		@DisplayName("업로드 유예·아카이브 상태의 행사는 칩 목록에 담기지 않는다")
		void 업로드_유예_상태의_행사는_칩_목록에_담기지_않는다() {
			EventSeries series = 시리즈();
			EventOccurrence 유예 = 회차(series, "유예 행사", "부산", NOW.minusDays(10), NOW.minusDays(1));
			EventOccurrence 아카이브 = 회차(series, "아카이브 행사", "부산", NOW.minusDays(100), NOW.minusDays(90));
			EventOccurrence 진행중 = 회차(series, "진행 중 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));

			List<EventOccurrenceChipResponseDto> chips = service().getOccurrencesInViewport(바다뷰포트());

			assertThat(회차ids(chips, List.of(유예, 아카이브, 진행중))).containsExactly(진행중.getId());
		}

		@Test
		@DisplayName("목록의 상태는 예정·진행 중 두 값뿐이고 파생 계산을 그대로 쓴다")
		void 목록의_상태는_파생_계산을_공유한다() {
			EventSeries series = 시리즈();
			EventOccurrence 예정 = 회차(series, "예정 행사", "부산", NOW.plusDays(1), NOW.plusDays(2));
			EventOccurrence 진행중 = 회차(series, "진행 중 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));

			List<EventOccurrenceChipResponseDto> chips = service().getOccurrencesInViewport(바다뷰포트()).stream()
				.filter(chip -> chip.occurrenceId().equals(예정.getId())
					|| chip.occurrenceId().equals(진행중.getId()))
				.toList();

			assertThat(chips).extracting(EventOccurrenceChipResponseDto::status)
				.containsExactlyInAnyOrder("UPCOMING", "LIVE");
		}

		@Test
		@DisplayName("뒤집힌 뷰포트는 13400으로 거절된다")
		void 뒤집힌_뷰포트는_13400으로_거절된다() {
			assertThatThrownBy(() -> service()
				.getOccurrencesInViewport(new ViewportBounds(34.5, 125.0, 34.0, 125.5)))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.INVALID_VIEWPORT);
		}

		@Test
		@DisplayName("WGS84 정의역 밖 좌표는 13400으로 거절된다")
		void WGS84_정의역_밖_좌표는_13400으로_거절된다() {
			assertThatThrownBy(() -> service()
				.getOccurrencesInViewport(new ViewportBounds(-91.0, 125.0, 34.0, 125.5)))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.INVALID_VIEWPORT);
		}

		@Test
		@DisplayName("한 변이 정확히 0.5도인 뷰포트는 허용된다")
		void 한_변이_정확히_0_5도인_뷰포트는_허용된다() {
			assertThatCode(() -> service()
				.getOccurrencesInViewport(new ViewportBounds(34.0, 125.0, 34.5, 125.5)))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("한 변이 0.5도를 넘는 뷰포트는 13401로 거절된다")
		void 한_변이_0_5도를_넘는_뷰포트는_13401로_거절된다() {
			assertThatThrownBy(() -> service()
				.getOccurrencesInViewport(new ViewportBounds(34.0, 125.0, 34.51, 125.5)))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.VIEWPORT_TOO_LARGE);
		}
	}

	@Nested
	@DisplayName("행사 회차 상세 (API 2)")
	class Detail {

		@Test
		@DisplayName("없는 회차 id 조회는 13404로 실패한다")
		void 없는_회차_id_조회는_13404로_실패한다() {
			assertThatThrownBy(() -> service().getOccurrenceDetail(-1L, null))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
		}

		@Test
		@DisplayName("미노출 예정 회차의 상세 조회는 없는 회차와 똑같이 13404다 (존재 은닉)")
		void 미노출_예정_회차의_상세_조회는_13404로_실패한다() {
			EventOccurrence 미노출 = 회차(시리즈(), "미노출 행사", "부산", NOW.plusDays(15), NOW.plusDays(16));

			assertThatThrownBy(() -> service().getOccurrenceDetail(미노출.getId(), null))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
		}

		@Test
		@DisplayName("상세 응답에 같은 시리즈의 이전 회차만 최신순으로 담긴다")
		void 상세_응답에_같은_시리즈의_이전_회차만_최신순으로_담긴다() {
			// 검증: FR-EVENT-07
			EventSeries series = 시리즈();
			EventOccurrence 재작년 = 회차(series, "재작년 회차", "부산", NOW.minusDays(700), NOW.minusDays(695));
			EventOccurrence 작년 = 회차(series, "작년 회차", "부산", NOW.minusDays(365), NOW.minusDays(360));
			EventOccurrence 올해 = 회차(series, "올해 회차", "부산", NOW.minusDays(1), NOW.plusDays(1));
			EventOccurrence 내년 = 회차(series, "내년 회차", "부산", NOW.plusDays(365), NOW.plusDays(370));
			EventOccurrence 남의시리즈 = 회차(시리즈(), "다른 시리즈", "부산", NOW.minusDays(400), NOW.minusDays(395));

			EventOccurrenceDetailResponseDto detail = service().getOccurrenceDetail(올해.getId(), null);

			assertThat(detail.previousOccurrences())
				.extracting(EventOccurrenceDetailResponseDto.PreviousOccurrenceDto::occurrenceId)
				.containsExactly(작년.getId(), 재작년.getId())
				.doesNotContain(내년.getId(), 남의시리즈.getId());
			assertThat(detail.seriesId()).isEqualTo(series.getId());
		}

		@Test
		@DisplayName("이전 회차의 시작일이 같으면 회차 id 내림차순 타이브레이커로 결정적이다")
		void 이전_회차는_시작일_동률에서_id_내림차순이다() {
			EventSeries series = 시리즈();
			EventOccurrence 먼저 = 회차(series, "동시 지난 회차 A", "부산", NOW.minusDays(365), NOW.minusDays(360));
			EventOccurrence 나중 = 회차(series, "동시 지난 회차 B", "부산", NOW.minusDays(365), NOW.minusDays(360));
			EventOccurrence 올해 = 회차(series, "올해 회차", "부산", NOW.minusDays(1), NOW.plusDays(1));

			assertThat(service().getOccurrenceDetail(올해.getId(), null).previousOccurrences())
				.extracting(EventOccurrenceDetailResponseDto.PreviousOccurrenceDto::occurrenceId)
				.containsExactly(나중.getId(), 먼저.getId());
		}

		@Test
		@DisplayName("상세의 상태와 업로드 마감은 파생 계산 그대로다 (유예 회차도 조회된다)")
		void 상세의_상태는_파생_계산을_공유한다() {
			EventOccurrence 유예 = 회차(시리즈(), "유예 행사", "부산", NOW.minusDays(10), NOW.minusDays(1));

			EventOccurrenceDetailResponseDto detail = service().getOccurrenceDetail(유예.getId(), null);

			assertThat(detail.status()).isEqualTo("UPLOAD_GRACE");
			assertThat(detail.uploadClosesAt()).isEqualTo(NOW.minusDays(1).plusDays(30));
		}

		@Test
		@DisplayName("구독하지 않았거나 비로그인이면 알림 구독 여부가 false 다")
		void 구독하지_않았으면_notificationOn은_false다() {
			EventOccurrence 진행중 = 회차(시리즈(), "진행 중 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));

			assertThat(service().getOccurrenceDetail(진행중.getId(), null).notificationOn()).isFalse();
			assertThat(service().getOccurrenceDetail(진행중.getId(), 사용자()).notificationOn()).isFalse();
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("구독한 사용자의 헤더 notificationOn 은 true 다 (MSG-442 배선)")
		void 구독한_사용자의_헤더_notificationOn은_true다() {
			EventOccurrence 진행중 = 회차(시리즈(), "진행 중 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));
			Long userId = 사용자();
			subscriptionRepository.insertSubscription(userId, 진행중.getId());

			assertThat(service().getOccurrenceDetail(진행중.getId(), userId).notificationOn()).isTrue();
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("종료된 회차는 구독 행이 있어도 헤더가 false 다 — 조회 시각과 같은 시각으로 파생 판정")
		void 종료된_회차는_구독_행이_있어도_헤더가_false다() {
			EventOccurrence 유예 = 회차(시리즈(), "유예 행사", "부산", NOW.minusDays(10), NOW.minusDays(1));
			Long userId = 사용자();
			subscriptionRepository.insertSubscription(userId, 유예.getId());

			EventOccurrenceDetailResponseDto detail = service().getOccurrenceDetail(유예.getId(), userId);

			assertThat(detail.status()).isEqualTo("UPLOAD_GRACE");
			assertThat(detail.notificationOn()).isFalse();
		}
	}

	@Nested
	@DisplayName("위치 목록 (API 3)")
	class Locations {

		@Test
		@DisplayName("없는 회차와 미노출 예정 회차의 위치 목록 조회는 13404로 실패한다")
		void 미노출_예정_회차의_위치_목록_조회는_13404로_실패한다() {
			// 검증: FR-EVENT-02
			EventOccurrence 미노출 = 회차(시리즈(), "미노출 행사", "부산", NOW.plusDays(15), NOW.plusDays(16));
			위치(미노출, "미노출 위치", 1, 격자(0, 0));

			assertThatThrownBy(() -> service().getLocations(미노출.getId()))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
			assertThatThrownBy(() -> service().getLocations(-1L))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
		}

		@Test
		@DisplayName("위치가 없는 회차는 빈 배열이다")
		void 위치가_없는_회차는_빈_배열이다() {
			EventOccurrence 진행중 = 회차(시리즈(), "위치 없는 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));

			assertThat(service().getLocations(진행중.getId())).isEmpty();
		}

		@Test
		@DisplayName("표시 순서 오름차순 → 동률이면 위치 id 오름차순으로 정렬된다")
		void 위치_목록은_표시_순서와_id_타이브레이커로_정렬된다() {
			EventOccurrence 진행중 = 회차(시리즈(), "정렬 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));
			EventLocation 셋째 = 위치(진행중, "셋째", 5, 격자(0, 0));
			EventLocation 첫째 = 위치(진행중, "첫째", 1, 격자(0, 1));
			EventLocation 둘째 = 위치(진행중, "둘째", 5, 격자(0, 2));

			assertThat(service().getLocations(진행중.getId()))
				.extracting(EventLocationResponseDto::locationId)
				.containsExactly(첫째.getId(), 셋째.getId(), 둘째.getId());
		}

		@Test
		@DisplayName("한 회차의 서로 떨어진 두 위치는 격자 영역이 격리된다")
		void 한_회차의_서로_떨어진_두_위치는_격자_영역이_격리된다() {
			EventOccurrence 진행중 = 회차(시리즈(), "두 위치 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));
			EventLocation 서쪽 = 위치(진행중, "서쪽 위치", 1, 격자(0, 0), 격자(0, 1));
			EventLocation 동쪽 = 위치(진행중, "동쪽 위치", 2, 격자(5, 5), 격자(5, 6));

			List<EventLocationResponseDto> locations = service().getLocations(진행중.getId());

			assertThat(locations.get(0).gridIds()).containsExactlyInAnyOrder(격자(0, 0), 격자(0, 1));
			assertThat(locations.get(1).gridIds()).containsExactlyInAnyOrder(격자(5, 5), 격자(5, 6));
			assertThat(locations.get(0).representativeGridId())
				.isEqualTo(서쪽.getRepresentativeGridId())
				.isIn(locations.get(0).gridIds());
			assertThat(locations.get(1).representativeGridId()).isEqualTo(동쪽.getRepresentativeGridId());
		}

		@Test
		@DisplayName("영상 수는 위치별 실측이고 영상이 없는 위치는 0으로 채워진다")
		void 위치_목록의_영상_수는_단일_그룹_집계로_계산된다() {
			EventOccurrence 진행중 = 회차(시리즈(), "영상 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));
			EventLocation 영상둘 = 위치(진행중, "영상 둘", 1, 격자(0, 0));
			위치(진행중, "영상 없음", 2, 격자(0, 1));
			노출영상(영상둘);
			노출영상(영상둘);

			assertThat(service().getLocations(진행중.getId()))
				.extracting(EventLocationResponseDto::videoCount)
				.containsExactly(2L, 0L);
		}

		@Test
		@DisplayName("피드에 노출되지 않는 영상은 영상 수에서도 빠진다 (ACTIVE·PUBLIC·READY 게이트)")
		void 피드에_노출되지_않는_영상은_영상_수에서도_빠진다() {
			EventOccurrence 진행중 = 회차(시리즈(), "게이트 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));
			EventLocation 위치 = 위치(진행중, "게이트 위치", 1, 격자(0, 0));
			노출영상(위치);
			비공개영상(위치);
			인코딩중영상(위치);
			삭제영상(위치);

			assertThat(service().getLocations(진행중.getId()))
				.extracting(EventLocationResponseDto::videoCount)
				.containsExactly(1L);
		}

		@Test
		@DisplayName("새 회차를 만들어도 이전 회차의 위치와 영상 수가 현재 회차에 섞이지 않는다")
		void 새_회차를_만들어도_이전_회차의_위치와_영상_수가_현재_회차에_섞이지_않는다() {
			EventSeries series = 시리즈();
			EventOccurrence 작년 = 회차(series, "작년 회차", "부산", NOW.minusDays(365), NOW.minusDays(360));
			EventOccurrence 올해 = 회차(series, "올해 회차", "부산", NOW.minusDays(1), NOW.plusDays(1));
			EventLocation 작년위치 = 위치(작년, "작년 위치", 1, 격자(0, 0));
			EventLocation 올해위치 = 위치(올해, "올해 위치", 1, 격자(1, 0));
			노출영상(작년위치);
			노출영상(작년위치);
			노출영상(올해위치);

			assertThat(service().getLocations(올해.getId()))
				.extracting(EventLocationResponseDto::locationId, EventLocationResponseDto::videoCount)
				.containsExactly(tuple(올해위치.getId(), 1L));
			assertThat(service().getLocations(작년.getId()))
				.extracting(EventLocationResponseDto::videoCount)
				.containsExactly(2L);
		}

		@Test
		@DisplayName("위치 목록 응답에 대표 격자 기준 표시명 재료가 담긴다 (구역 무귀속이면 null)")
		void 위치_목록_응답에_대표_격자_기준_표시명_재료가_담긴다() {
			EventOccurrence 진행중 = 회차(시리즈(), "표시명 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));
			String 구역안 = 격자(0, 0);
			구역(구역안);
			위치(진행중, "구역 안 위치", 1, 구역안);
			위치(진행중, "구역 밖 위치", 2, 격자(3, 3));

			List<EventLocationResponseDto> locations = service().getLocations(진행중.getId());

			assertThat(locations.get(0).zoneName()).isEqualTo("테스트구역");
			assertThat(locations.get(0).zoneCell()).isEqualTo("A-1");
			assertThat(locations.get(1).zoneName()).isNull();
			assertThat(locations.get(1).zoneCell()).isNull();
			// 서해 먼바다라 행정동이 없다 — 진짜 무귀속만 null 이라는 계약의 확인이다.
			assertThat(locations.get(0).regionName()).isNull();
		}

		@Test
		@DisplayName("grids 행이 없는 행정동 안 대표 격자도 regionName 이 담긴다 (lazy insert 무관)")
		void grids_행이_없는_행정동_안_대표_격자도_regionName이_담긴다() {
			String 대표 = 격자(50, 50);
			합성행정동(50, 50);
			assertThat(gridRepository.findById(대표))
				.as("전제: 아무도 영상을 올리지 않은 격자라 grids row 가 없다")
				.isEmpty();
			EventOccurrence 진행중 = 회차(시리즈(), "행정동 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));
			위치(진행중, "행정동 위치", 1, 대표);

			assertThat(service().getLocations(진행중.getId()).get(0).regionName())
				.as("행사 대표 격자에는 grids row 가 없을 수 있다 — 중심점 재판정이라야 이름이 나온다")
				.isEqualTo(REGION_NAME);
		}
	}

	@Nested
	@DisplayName("격자 역조회 (API 4)")
	class ReverseLookup {

		@Test
		@DisplayName("행사 영역의 서로 다른 두 격자를 역조회하면 같은 위치가 나온다")
		void 행사_영역의_서로_다른_두_격자를_역조회하면_같은_위치가_나온다() {
			// 검증: FR-EVENT-08
			EventOccurrence 진행중 = 회차(시리즈(), "영역 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));
			EventLocation 위치 = 위치(진행중, "넓은 위치", 1, 격자(0, 0), 격자(0, 1), 격자(1, 0));

			assertThat(service().getLocationsByGrid(격자(0, 1)))
				.extracting(GridEventLocationResponseDto::locationId)
				.containsExactly(위치.getId());
			assertThat(service().getLocationsByGrid(격자(1, 0)))
				.extracting(GridEventLocationResponseDto::locationId,
					GridEventLocationResponseDto::representativeGridId)
				.containsExactly(tuple(위치.getId(), 격자(0, 0)));
		}

		@Test
		@DisplayName("어떤 행사 위치에도 속하지 않는 격자의 역조회는 빈 배열이다")
		void 어떤_행사_위치에도_속하지_않는_격자의_역조회는_빈_배열이다() {
			assertThat(service().getLocationsByGrid(격자(999, 999))).isEmpty();
		}

		@Test
		@DisplayName("격자 포맷이 아닌 임의 문자열 역조회도 빈 배열이다 (표시명 판정 전에 끝난다)")
		void 격자_포맷이_아닌_임의_문자열_역조회도_빈_배열이다() {
			assertThat(service().getLocationsByGrid("not-a-grid-id")).isEmpty();
		}

		@Test
		@DisplayName("미래·현재·지난 회차가 혼재한 격자의 역조회는 진행 중 회차가 첫 항목이다")
		void 미래_현재_지난_회차가_혼재한_격자의_역조회는_진행_중_회차가_첫_항목이다() {
			EventSeries series = 시리즈();
			String 격자 = 격자(0, 0);
			EventOccurrence 아카이브 = 회차(series, "아카이브", "부산", NOW.minusDays(100), NOW.minusDays(90));
			EventOccurrence 예정 = 회차(series, "예정", "부산", NOW.plusDays(3), NOW.plusDays(4));
			EventOccurrence 진행중 = 회차(series, "진행 중", "부산", NOW.minusDays(1), NOW.plusDays(1));
			위치(아카이브, "아카이브 위치", 1, 격자);
			위치(예정, "예정 위치", 1, 격자);
			위치(진행중, "진행 중 위치", 1, 격자);

			assertThat(service().getLocationsByGrid(격자))
				.extracting(GridEventLocationResponseDto::occurrenceStatus)
				.containsExactly("LIVE", "UPCOMING", "ARCHIVED");
		}

		@Test
		@DisplayName("유예 회차와 노출 중인 예정 회차가 공존하면 예정 회차가 첫 항목이다")
		void 유예_회차와_노출_중인_예정_회차가_공존하면_예정_회차가_첫_항목이다() {
			EventSeries series = 시리즈();
			String 격자 = 격자(0, 0);
			EventOccurrence 유예 = 회차(series, "유예", "부산", NOW.minusDays(10), NOW.minusDays(1));
			EventOccurrence 예정 = 회차(series, "예정", "부산", NOW.plusDays(3), NOW.plusDays(4));
			위치(유예, "유예 위치", 1, 격자);
			위치(예정, "예정 위치", 1, 격자);

			assertThat(service().getLocationsByGrid(격자))
				.extracting(GridEventLocationResponseDto::occurrenceId)
				.containsExactly(예정.getId(), 유예.getId());
		}

		@Test
		@DisplayName("유예 회차와 미노출 예정 회차가 공존하면 유예 회차가 첫 항목이다 (미노출은 제외)")
		void 유예_회차와_미노출_예정_회차가_공존하면_유예_회차가_첫_항목이다() {
			EventSeries series = 시리즈();
			String 격자 = 격자(0, 0);
			EventOccurrence 유예 = 회차(series, "유예", "부산", NOW.minusDays(10), NOW.minusDays(1));
			EventOccurrence 미노출 = 회차(series, "미노출 예정", "부산", NOW.plusDays(20), NOW.plusDays(21));
			위치(유예, "유예 위치", 1, 격자);
			위치(미노출, "미노출 위치", 1, 격자);

			assertThat(service().getLocationsByGrid(격자))
				.extracting(GridEventLocationResponseDto::occurrenceId)
				.containsExactly(유예.getId());
		}

		@Test
		@DisplayName("같은 격자에 예정 회차 둘이 연결되면 임박한 회차가 먼저다")
		void 같은_격자에_예정_회차_둘이_연결되면_임박한_회차가_먼저다() {
			EventSeries series = 시리즈();
			String 격자 = 격자(0, 0);
			EventOccurrence 나중 = 회차(series, "나중 예정", "부산", NOW.plusDays(10), NOW.plusDays(11));
			EventOccurrence 임박 = 회차(series, "임박 예정", "부산", NOW.plusDays(2), NOW.plusDays(3));
			위치(나중, "나중 위치", 1, 격자);
			위치(임박, "임박 위치", 1, 격자);

			assertThat(service().getLocationsByGrid(격자))
				.extracting(GridEventLocationResponseDto::occurrenceId)
				.containsExactly(임박.getId(), 나중.getId());
		}

		@Test
		@DisplayName("지난 회차끼리는 최근 회차가 먼저다 (시작일 내림차순)")
		void 지난_회차끼리는_최근_회차가_먼저다() {
			EventSeries series = 시리즈();
			String 격자 = 격자(0, 0);
			EventOccurrence 오래된 = 회차(series, "오래된", "부산", NOW.minusDays(200), NOW.minusDays(190));
			EventOccurrence 최근 = 회차(series, "최근", "부산", NOW.minusDays(100), NOW.minusDays(90));
			위치(오래된, "오래된 위치", 1, 격자);
			위치(최근, "최근 위치", 1, 격자);

			assertThat(service().getLocationsByGrid(격자))
				.extracting(GridEventLocationResponseDto::occurrenceId)
				.containsExactly(최근.getId(), 오래된.getId());
		}

		@Test
		@DisplayName("상태와 시작일이 모두 같으면 회차 id 내림차순 타이브레이커로 결정적이다")
		void 역조회는_상태와_시작일_동률에서_회차_id_내림차순이다() {
			EventSeries series = 시리즈();
			String 격자 = 격자(0, 0);
			EventOccurrence 먼저 = 회차(series, "동시 진행 A", "부산", NOW.minusDays(1), NOW.plusDays(1));
			EventOccurrence 나중 = 회차(series, "동시 진행 B", "부산", NOW.minusDays(1), NOW.plusDays(1));
			위치(먼저, "먼저 위치", 1, 격자);
			위치(나중, "나중 위치", 1, 격자);

			assertThat(service().getLocationsByGrid(격자))
				.extracting(GridEventLocationResponseDto::occurrenceId)
				.containsExactly(나중.getId(), 먼저.getId());
		}

		@Test
		@DisplayName("역조회 응답에 대표 격자 기준 표시명 재료가 담긴다")
		void 역조회_응답에_대표_격자_기준_표시명_재료가_담긴다() {
			EventOccurrence 진행중 = 회차(시리즈(), "표시명 행사", "부산", NOW.minusDays(1), NOW.plusDays(1));
			String 대표 = 격자(0, 0);
			구역(대표);
			위치(진행중, "구역 안 위치", 1, 대표, 격자(0, 1));

			GridEventLocationResponseDto found = service().getLocationsByGrid(격자(0, 1)).get(0);

			assertThat(found.zoneName()).isEqualTo("테스트구역");
			assertThat(found.zoneCell()).isEqualTo("A-1");
			assertThat(found.regionName()).isNull();
		}
	}

	@Nested
	@DisplayName("회차 위치 일괄 조회 (MSG-457 route 소비)")
	class LocationsBulk {

		// 검증: FR-ROUTE-03
		@Test
		@DisplayName("여러 회차의 위치를 단건 조회와 같은 정렬 계약으로 한 번에 준다")
		void 여러_회차의_위치를_정렬_계약대로_한_번에_준다() {
			EventSeries series = 시리즈();
			EventOccurrence 첫회차 = 회차(series, "행사 A", "부산", NOW.minusDays(1), NOW.plusDays(1));
			EventOccurrence 둘째회차 = 회차(series, "행사 B", "부산", NOW.minusDays(1), NOW.plusDays(1));
			위치(첫회차, "본무대", 2, 격자(0, 0));
			위치(첫회차, "먹거리존", 1, 격자(1, 1));
			위치(둘째회차, "포토존", 1, 격자(2, 2));

			Map<Long, List<EventQueryService.LocationPoint>> locations =
				service().getLocationsBulk(List.of(첫회차.getId(), 둘째회차.getId(), -1L));

			// 없는 회차(-1)는 키 자체가 없다 — 실패가 아니다.
			assertThat(locations.keySet()).containsExactlyInAnyOrder(첫회차.getId(), 둘째회차.getId());
			// display_order 오름차순 — 첫 항목이 진입 기본값이라는 단건 조회(getLocations)와 같은 계약이다.
			assertThat(locations.get(첫회차.getId()))
				.extracting(EventQueryService.LocationPoint::name)
				.containsExactly("먹거리존", "본무대");
			assertThat(locations.get(둘째회차.getId())).containsExactly(
				new EventQueryService.LocationPoint("포토존", 격자(2, 2)));
		}

		// 검증: FR-ROUTE-03
		@Test
		@DisplayName("미노출 예정 회차는 키 자체가 빠진다 — 단건 조회의 13404 존재 은닉과 같은 결")
		void 미노출_예정_회차는_키_자체가_빠진다() {
			EventOccurrence 미노출 = 회차(시리즈(), "미노출 행사", "부산", NOW.plusDays(15), NOW.plusDays(16));
			위치(미노출, "미노출 위치", 1, 격자(0, 0));

			assertThat(service().getLocationsBulk(List.of(미노출.getId()))).isEmpty();
		}
	}
}
