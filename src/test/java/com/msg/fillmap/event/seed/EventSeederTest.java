package com.msg.fillmap.event.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventLocationGrid;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * EventSeeder 와 V39 DDL 제약 (실 PostgreSQL, MSG-438). ZoneSeeder·RegionSeeder 선례를 따른다.
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg438-*)와 국내 정의역 밖 합성 격자 인덱스(9만대)만 쓰고 @Transactional 롤백한다.
 * 지연 제약(uq_event_grid_per_occ · fk_event_loc_rep_grid)은 커밋 시점 검증이라 롤백 테스트에선 안 터지므로,
 * {@code SET CONSTRAINTS ALL IMMEDIATE} 로 커밋 시점 검증을 트랜잭션 안으로 당겨 확인한다.
 */
@SpringBootTest
@Transactional
@DisplayName("EventSeeder · V39 DDL 제약 (실 PostgreSQL) — 합성 자연키·롤백 격리")
class EventSeederTest {

	/** 합성 격자 인덱스 — 국내 실데이터(y 16000~20000대, x 9000~12000대)와 절대 겹치지 않는 대역. */
	private static final int Y = 90000;
	private static final int X = 90500;

	/** 서해 먼바다 합성 좌표 (FestivalMissionSeederIntegrationTest 선례) — 영상 픽스처 전용. */
	private static final double 합성_LAT = 34.0;
	private static final double 합성_LON = 125.0;

	private static final String 시작_KST = "2026-10-06T10:00:00+09:00";
	private static final String 종료_KST = "2026-10-15T22:00:00+09:00";

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
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager em;

	private EventSeeder seeder(boolean enabled, String path) {
		EventSeeder seeder = new EventSeeder(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository, eventVideoRepository, objectMapper);
		ReflectionTestUtils.setField(seeder, "enabled", enabled);
		ReflectionTestUtils.setField(seeder, "path", path);
		return seeder;
	}

	private EventSeeder seeder() {
		return seeder(true, "unused");
	}

	/* ---------- 시드 문서 조립 ---------- */

	private Resource json(EventSeed... seeds) {
		return new ByteArrayResource(objectMapper.writeValueAsBytes(seeds));
	}

	private EventSeed 시리즈(String key, EventSeed.Occurrence... occurrences) {
		return new EventSeed(key, key + " 시리즈", List.of(occurrences));
	}

	private EventSeed.Occurrence 회차(String key, EventSeed.Location... locations) {
		return 회차(key, 시작_KST, 종료_KST, locations);
	}

	private EventSeed.Occurrence 회차(String key, String startsAt, String endsAt, EventSeed.Location... locations) {
		return new EventSeed.Occurrence(key, key + " 행사", "부산", startsAt, endsAt,
			new EventSeed.Rect(Y - 100, Y + 100, X - 100, X + 100), List.of(locations));
	}

	private EventSeed.Location 위치(String key, EventSeed.Rect... rects) {
		return new EventSeed.Location(key, key + " 장소", "ETC", null, 1, List.of(rects), null);
	}

	private EventSeed.Rect 사각형(int minY, int maxY, int minX, int maxX) {
		return new EventSeed.Rect(minY, maxY, minX, maxX);
	}

	/* ---------- 조회 보조 ---------- */

	private EventLocation 위치조회(String locationKey) {
		em.flush();
		em.clear();
		return locationRepository.findByLocationKey(locationKey).orElseThrow();
	}

	private List<String> 격자조회(String locationKey) {
		return locationGridRepository.findByIdEventLocationId(위치조회(locationKey).getId()).stream()
			.map(EventLocationGrid::getGridId)
			.sorted()
			.toList();
	}

	/**
	 * event_videos 의 video_id FK 충족용 최소 영상. 격자는 서해 먼바다 합성 좌표라 육상 실데이터와 겹치지 않고,
	 * 이 테스트가 보는 건 복합 FK 하나뿐이라 영상의 격자와 행사 영역은 무관하다.
	 */
	private Long 영상생성() {
		Long userId = userRepository.save(User.createLocalUser(
			"msg438-vfk-" + UUID.randomUUID() + "@example.com", "hash", "테스터")).getId();
		String gridId = GridEncoder.encode(합성_LAT, 합성_LON);
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		Video video = Video.create(userId, gridId, "videos/original/" + UUID.randomUUID() + ".mp4",
			GeoSupport.toPoint(합성_LAT, 합성_LON), (short) 10, LocalDateTime.now(ZoneOffset.UTC), Visibility.PUBLIC);
		return videoRepository.save(video).getId();
	}

	/** 지연 제약의 커밋 시점 검증을 트랜잭션 안으로 당긴다. 위반이면 여기서 예외가 난다. */
	private void 지연제약검증() {
		em.flush();
		em.createNativeQuery("SET CONSTRAINTS ALL IMMEDIATE").executeUpdate();
	}

	@Nested
	@DisplayName("게이트와 파일 읽기")
	class Gate {

		@Test
		@DisplayName("seed_enabled 가 off 면 시더가 동작하지 않는다 (없는 경로여도 읽지 않아 무예외)")
		void seed_enabled가_off면_시더가_동작하지_않는다() {
			EventSeeder off = seeder(false, "seed/does-not-exist.json");

			assertThatCode(() -> off.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("시드 파일이 없으면 기동이 실패한다 (fail-fast, ZoneSeeder 선례)")
		void 시드_파일이_없으면_기동이_실패한다() {
			EventSeeder on = seeder(true, "seed/does-not-exist.json");

			assertThatThrownBy(() -> on.run(new DefaultApplicationArguments()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("행사 시드 파일");
		}

		@Test
		@DisplayName("빈 배열은 정상이다 (등재할 행사가 없는 상태)")
		void 빈_배열은_정상이다() {
			assertThat(seeder().seed(new ByteArrayResource("[]".getBytes()))).isZero();
		}

		@Test
		@DisplayName("시딩은 advisory lock 을 잡아 인스턴스 간 동시 실행을 직렬화한다")
		void 시딩은_advisory_lock을_잡아_동시_실행을_직렬화한다() {
			seeder().seed(json(시리즈("msg438-lock", 회차("msg438-lock-2026",
				위치("msg438-lock-loc", 사각형(Y, Y + 2, X, X + 2))))));

			// 트랜잭션 종료 시 자동 해제되는 xact lock 이라, 아직 열린 이 트랜잭션에서 보유 중이어야 한다.
			Number held = (Number) em.createNativeQuery(
					"SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid()")
				.getSingleResult();
			assertThat(held.longValue()).isPositive();
		}
	}

	@Nested
	@DisplayName("적재와 멱등")
	class Idempotency {

		// 검증: FR-EVENT-08
		@Test
		@DisplayName("시드를 두 번 실행해도 결과가 같다 (멱등)")
		void 시드를_두_번_실행해도_결과가_같다() {
			Resource seed = json(시리즈("msg438-idem", 회차("msg438-idem-2026",
				위치("msg438-idem-loc", 사각형(Y, Y + 4, X, X + 4)))));

			seeder().seed(seed);
			String 첫_대표 = 위치조회("msg438-idem-loc").getRepresentativeGridId();
			List<String> 첫_격자 = 격자조회("msg438-idem-loc");

			seeder().seed(json(시리즈("msg438-idem", 회차("msg438-idem-2026",
				위치("msg438-idem-loc", 사각형(Y, Y + 4, X, X + 4))))));

			assertThat(위치조회("msg438-idem-loc").getRepresentativeGridId()).isEqualTo(첫_대표);
			assertThat(격자조회("msg438-idem-loc")).isEqualTo(첫_격자).hasSize(25);
			assertThat(첫_대표).isEqualTo((Y + 2) + "_" + (X + 2));   // 홀수 5×5 정중앙
		}

		@Test
		@DisplayName("파일에서 사라진 회차는 삭제되지 않는다 (아카이브 보존)")
		void 파일에서_사라진_회차는_삭제되지_않는다() {
			seeder().seed(json(시리즈("msg438-keep", 회차("msg438-keep-2025",
				위치("msg438-keep-loc-a", 사각형(Y, Y + 2, X, X + 2))))));

			seeder().seed(json(시리즈("msg438-keep", 회차("msg438-keep-2026",
				위치("msg438-keep-loc-b", 사각형(Y + 10, Y + 12, X, X + 2))))));

			em.flush();
			em.clear();
			assertThat(occurrenceRepository.findByOccurrenceKey("msg438-keep-2025")).isPresent();
			assertThat(locationRepository.findByLocationKey("msg438-keep-loc-a")).isPresent();
		}

		@Test
		@DisplayName("영역이 줄어든 재시드는 제거된 격자 행을 지우고 대표 격자를 다시 계산한다")
		void 영역이_줄어든_재시드는_제거된_격자_행을_지우고_대표_격자를_다시_계산한다() {
			seeder().seed(json(시리즈("msg438-shrink", 회차("msg438-shrink-2026",
				위치("msg438-shrink-loc", 사각형(Y, Y + 4, X, X + 4))))));
			assertThat(위치조회("msg438-shrink-loc").getRepresentativeGridId()).isEqualTo((Y + 2) + "_" + (X + 2));

			seeder().seed(json(시리즈("msg438-shrink", 회차("msg438-shrink-2026",
				위치("msg438-shrink-loc", 사각형(Y, Y + 2, X, X + 2))))));

			assertThat(격자조회("msg438-shrink-loc")).hasSize(9)
				.doesNotContain((Y + 4) + "_" + (X + 4));
			assertThat(위치조회("msg438-shrink-loc").getRepresentativeGridId()).isEqualTo((Y + 1) + "_" + (X + 1));
			assertThatCode(EventSeederTest.this::지연제약검증).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("격자를 다른 위치로 옮기는 재시드가 한 트랜잭션에서 성공한다 (지연 UNIQUE, flush 순서 무관)")
		void 격자를_다른_위치로_옮기는_재시드가_한_트랜잭션에서_성공한다() {
			// A 가 3열, B 가 3열을 갖다가 가운데 열이 A → B 로 넘어간다. Hibernate 는 insert 를 delete 보다
			// 먼저 flush 하므로 즉시 제약이면 이 재시드가 UNIQUE 위반으로 깨진다.
			seeder().seed(json(시리즈("msg438-move", 회차("msg438-move-2026",
				위치("msg438-move-a", 사각형(Y, Y + 2, X, X + 2)),
				위치("msg438-move-b", 사각형(Y, Y + 2, X + 3, X + 5))))));

			seeder().seed(json(시리즈("msg438-move", 회차("msg438-move-2026",
				위치("msg438-move-a", 사각형(Y, Y + 2, X, X + 1)),
				위치("msg438-move-b", 사각형(Y, Y + 2, X + 2, X + 5))))));

			assertThatCode(EventSeederTest.this::지연제약검증).doesNotThrowAnyException();
			assertThat(격자조회("msg438-move-a")).hasSize(6).doesNotContain(Y + "_" + (X + 2));
			assertThat(격자조회("msg438-move-b")).hasSize(12).contains(Y + "_" + (X + 2));
		}

		@Test
		@DisplayName("기존 회차의 시작 시각을 바꾸는 재시드가 성공하고 visible_from 도 함께 이동한다")
		void 기존_회차의_시작_시각을_바꾸는_재시드가_성공하고_visible_from도_함께_이동한다() {
			seeder().seed(json(시리즈("msg438-resched", 회차("msg438-resched-2026", 시작_KST, 종료_KST,
				위치("msg438-resched-loc", 사각형(Y, Y + 2, X, X + 2))))));

			seeder().seed(json(시리즈("msg438-resched", 회차("msg438-resched-2026",
				"2026-11-06T10:00:00+09:00", "2026-11-15T22:00:00+09:00",
				위치("msg438-resched-loc", 사각형(Y, Y + 2, X, X + 2))))));

			em.flush();
			em.clear();
			EventOccurrence occurrence = occurrenceRepository.findByOccurrenceKey("msg438-resched-2026").orElseThrow();
			assertThat(occurrence.getStartsAt()).isEqualTo(LocalDateTime.of(2026, 11, 6, 1, 0));
			assertThat(occurrence.getVisibleFrom()).isEqualTo(occurrence.getStartsAt().minusDays(14));
		}
	}

	@Nested
	@DisplayName("시각 변환 (MSG-376 체계)")
	class Times {

		@Test
		@DisplayName("visible_from 은 항상 시작 2주 전으로 계산돼 저장된다 (시드 입력이 아니다)")
		void visible_from은_항상_시작_2주_전으로_계산돼_저장된다() {
			seeder().seed(json(시리즈("msg438-visible", 회차("msg438-visible-2026",
				위치("msg438-visible-loc", 사각형(Y, Y + 2, X, X + 2))))));

			em.flush();
			em.clear();
			EventOccurrence occurrence = occurrenceRepository.findByOccurrenceKey("msg438-visible-2026").orElseThrow();
			assertThat(occurrence.getVisibleFrom()).isEqualTo(occurrence.getStartsAt().minusDays(14));
			// DDL CHECK 등식이 실제로 걸려 있는지 — 우회 UPDATE 는 거절돼야 한다.
			assertThatThrownBy(() -> {
				em.createNativeQuery("UPDATE event_occurrences SET visible_from = starts_at WHERE id = :id")
					.setParameter("id", occurrence.getId())
					.executeUpdate();
				em.flush();
			}).hasMessageContaining("chk_event_occ_visible");
		}

		@Test
		@DisplayName("시드 시각의 KST 오프셋 표기는 UTC 로 저장된다")
		void 시드_시각의_KST_오프셋_표기는_UTC로_저장된다() {
			seeder().seed(json(시리즈("msg438-utc", 회차("msg438-utc-2026", 시작_KST, 종료_KST,
				위치("msg438-utc-loc", 사각형(Y, Y + 2, X, X + 2))))));

			em.flush();
			em.clear();
			EventOccurrence occurrence = occurrenceRepository.findByOccurrenceKey("msg438-utc-2026").orElseThrow();
			assertThat(occurrence.getStartsAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 1, 0));    // 10:00 KST
			assertThat(occurrence.getEndsAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 13, 0));    // 22:00 KST
		}

		@Test
		@DisplayName("오프셋 없는 시드 시각은 파싱 실패로 기동이 멈춘다")
		void 오프셋_없는_시드_시각은_파싱_실패로_기동이_멈춘다() {
			Resource naive = json(시리즈("msg438-naive", 회차("msg438-naive-2026",
				"2026-10-06T10:00:00", 종료_KST,
				위치("msg438-naive-loc", 사각형(Y, Y + 2, X, X + 2)))));

			assertThatThrownBy(() -> seeder().seed(naive))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("오프셋 포함 ISO-8601");
		}
	}

	@Nested
	@DisplayName("형식 fail-fast")
	class FailFast {

		@Test
		@DisplayName("빈 영역 시드는 기동이 실패한다")
		void 빈_영역_시드는_기동이_실패한다() {
			EventSeed.Location 빈영역 = new EventSeed.Location(
				"msg438-empty-loc", "빈 영역", "ETC", null, 1, List.of(), null);

			assertThatThrownBy(() -> seeder().seed(json(시리즈("msg438-empty", 회차("msg438-empty-2026", 빈영역)))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("areaRects");
		}

		@Test
		@DisplayName("역전된 사각형 시드는 기동이 실패한다 (전개 결과가 빈 집합이 되어 오류가 숨는다)")
		void 역전된_사각형_시드는_기동이_실패한다() {
			Resource 역전 = json(시리즈("msg438-inv", 회차("msg438-inv-2026",
				위치("msg438-inv-loc", 사각형(Y + 4, Y, X, X + 2)))));

			assertThatThrownBy(() -> seeder().seed(역전))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("역전");
		}

		@Test
		@DisplayName("허용 범위 밖 격자 인덱스 시드는 기동이 실패한다")
		void 허용_범위_밖_격자_인덱스_시드는_기동이_실패한다() {
			Resource 범위밖 = json(시리즈("msg438-range", 회차("msg438-range-2026",
				위치("msg438-range-loc", 사각형(100_000, 100_002, X, X + 2)))));

			assertThatThrownBy(() -> seeder().seed(범위밖))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("허용 범위");
		}

		@Test
		@DisplayName("단일 사각형이 고유 셀 수 상한을 넘으면 전개 전에 거부된다 (OOM 1차 가드)")
		void 단일_사각형이_고유_셀_수_상한을_넘으면_전개_전에_거부된다() {
			Resource 과대 = json(시리즈("msg438-cap", 회차("msg438-cap-2026",
				위치("msg438-cap-loc", 사각형(Y, Y + 50, X, X + 50)))));   // 51 × 51 = 2601 칸

			assertThatThrownBy(() -> seeder().seed(과대))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("사각형 하나가 상한");
		}

		@Test
		@DisplayName("겹치는 사각형은 고유 셀 기준으로 세므로 상한 안이면 통과한다 (표현 무관 계약)")
		void 겹치는_사각형은_고유_셀_기준으로_세므로_상한_안이면_통과한다() {
			// 같은 50×50(2500칸)을 두 번 겹쳐 그린다 — 중복으로 세면 5000칸이라 거부될 표현이다.
			Resource 겹침 = json(시리즈("msg438-overlap", 회차("msg438-overlap-2026",
				위치("msg438-overlap-loc",
					사각형(Y, Y + 49, X, X + 49), 사각형(Y, Y + 49, X, X + 49)))));

			assertThatCode(() -> seeder().seed(겹침)).doesNotThrowAnyException();
			assertThat(격자조회("msg438-overlap-loc")).hasSize(2500);
		}

		@Test
		@DisplayName("합집합 고유 셀 수가 상한을 넘으면 기동이 실패한다")
		void 합집합_고유_셀_수가_상한을_넘으면_기동이_실패한다() {
			Resource 초과 = json(시리즈("msg438-union", 회차("msg438-union-2026",
				위치("msg438-union-loc",
					사각형(Y, Y + 49, X, X + 49),        // 2500칸
					사각형(Y + 50, Y + 50, X, X)))));    // 겹치지 않는 1칸 → 2501

			assertThatThrownBy(() -> seeder().seed(초과))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("고유 셀 수가 상한");
		}
	}

	@Nested
	@DisplayName("V39 DDL 제약")
	class Constraints {

		@Test
		@DisplayName("한 회차에서 두 위치에 같은 격자를 넣은 시드는 실패한다 (uq_event_grid_per_occ)")
		void 한_회차에서_두_위치에_같은_격자를_넣은_시드는_실패한다() {
			seeder().seed(json(시리즈("msg438-dup", 회차("msg438-dup-2026",
				위치("msg438-dup-a", 사각형(Y, Y + 2, X, X + 2)),
				위치("msg438-dup-b", 사각형(Y + 2, Y + 4, X + 2, X + 4))))));   // (Y+2, X+2) 가 겹친다

			assertThatThrownBy(EventSeederTest.this::지연제약검증)
				.hasMessageContaining("uq_event_grid_per_occ");
		}

		// 검증: FR-EVENT-07
		@Test
		@DisplayName("다른 회차는 같은 격자를 각자 가질 수 있다 (제약 범위가 회차 단위)")
		void 다른_회차는_같은_격자를_각자_가질_수_있다() {
			seeder().seed(json(시리즈("msg438-share",
				회차("msg438-share-2025", 위치("msg438-share-a", 사각형(Y, Y + 2, X, X + 2))),
				회차("msg438-share-2026", 위치("msg438-share-b", 사각형(Y, Y + 2, X, X + 2))))));

			assertThatCode(EventSeederTest.this::지연제약검증).doesNotThrowAnyException();
			assertThat(격자조회("msg438-share-a")).isEqualTo(격자조회("msg438-share-b"));
		}

		@Test
		@DisplayName("격자 행의 회차와 위치의 회차가 다르면 저장이 거절된다 (복합 FK)")
		void 격자_행의_회차와_위치의_회차가_다르면_저장이_거절된다() {
			seeder().seed(json(시리즈("msg438-fk",
				회차("msg438-fk-2025", 위치("msg438-fk-a", 사각형(Y, Y + 2, X, X + 2))),
				회차("msg438-fk-2026", 위치("msg438-fk-b", 사각형(Y + 10, Y + 12, X, X + 2))))));
			Long 위치A = 위치조회("msg438-fk-a").getId();
			Long 회차B = 위치조회("msg438-fk-b").getOccurrence().getId();

			assertThatThrownBy(() -> {
				em.createNativeQuery("""
						INSERT INTO event_location_grids (event_location_id, event_occurrence_id, grid_id)
						VALUES (:locationId, :occurrenceId, :gridId)""")
					.setParameter("locationId", 위치A)
					.setParameter("occurrenceId", 회차B)      // 위치 A 의 회차가 아니다
					.setParameter("gridId", (Y + 20) + "_" + X)
					.executeUpdate();
				em.flush();
			}).hasMessageContaining("fk_event_grid_location");
		}

		// 검증: FR-EVENT-07
		@Test
		@DisplayName("영상 연결의 회차와 위치의 회차가 다르면 저장이 거절된다 (복합 FK)")
		void 영상_연결의_회차와_위치의_회차가_다르면_저장이_거절된다() {
			seeder().seed(json(시리즈("msg438-vfk",
				회차("msg438-vfk-2025", 위치("msg438-vfk-a", 사각형(Y, Y + 2, X, X + 2))),
				회차("msg438-vfk-2026", 위치("msg438-vfk-b", 사각형(Y + 10, Y + 12, X, X + 2))))));
			Long 위치A = 위치조회("msg438-vfk-a").getId();
			Long 회차B = 위치조회("msg438-vfk-b").getOccurrence().getId();
			Long 영상id = 영상생성();

			assertThatThrownBy(() -> {
				em.createNativeQuery("""
						INSERT INTO event_videos (video_id, event_occurrence_id, event_location_id)
						VALUES (:videoId, :occurrenceId, :locationId)""")
					.setParameter("videoId", 영상id)
					.setParameter("occurrenceId", 회차B)      // 위치 A 의 회차가 아니다
					.setParameter("locationId", 위치A)
					.executeUpdate();
				em.flush();
			}).hasMessageContaining("fk_event_video_location");
		}

		@Test
		@DisplayName("대표 격자가 영역 격자 행 없이 커밋되면 지연 FK 가 거절한다")
		void 대표_격자가_영역_격자_행_없이_커밋되면_지연_FK가_거절한다() {
			seeder().seed(json(시리즈("msg438-orphan", 회차("msg438-orphan-2026",
				위치("msg438-orphan-loc", 사각형(Y, Y + 2, X, X + 2))))));
			Long 위치id = 위치조회("msg438-orphan-loc").getId();

			// 영역에 없는 격자를 대표로 바꾼다 — 즉시 제약이면 여기서, 지연이면 커밋 시점에 걸린다.
			em.createNativeQuery("UPDATE event_locations SET representative_grid_id = :gridId WHERE id = :id")
				.setParameter("gridId", (Y + 99) + "_" + X)
				.setParameter("id", 위치id)
				.executeUpdate();

			assertThatThrownBy(EventSeederTest.this::지연제약검증)
				.hasMessageContaining("fk_event_loc_rep_grid");
		}
	}

	@Nested
	@DisplayName("첫 시드 실값")
	class RealSeed {

		/** 스펙 "첫 시드 실값 초안"의 기준 위경도. 대표 격자는 전 위치가 홀수 직사각형이라 시더가 정중앙으로 정한다. */
		private static final Map<String, double[]> 기준_위경도 = Map.of(
			"biff-2026-cinema-center", new double[] {35.1717, 129.1240},
			"biff-2026-haeundae-event-zone", new double[] {35.1587, 129.1604},
			"biff-2026-nampo-biff-square", new double[] {35.0987, 129.0287},
			"busan-fireworks-2026-gwangalli", new double[] {35.1532, 129.1187},
			"busan-fireworks-2026-igidae", new double[] {35.1318, 129.1233},
			"busan-fireworks-2026-dongbaekseom", new double[] {35.1529, 129.1520},
			"seoul-fireworks-2026-yeouido", new double[] {37.5284, 126.9327});

		// 검증: FR-EVENT-08
		@Test
		@DisplayName("첫 시드 실값의 기준 위경도가 대표 격자와 일치한다 (GridEncoder 전수 재계산 대조)")
		void 첫_시드_실값의_기준_위경도가_초안_격자와_일치한다() {
			// 동봉 실데이터 적재 — 테스트 트랜잭션 롤백으로 DB 잔류가 없다 (ZoneSeederTest 선례).
			int locations = seeder().seed(new ClassPathResource("seed/events.json"));

			assertThat(locations).isEqualTo(기준_위경도.size());
			기준_위경도.forEach((locationKey, latLon) -> assertThat(위치조회(locationKey).getRepresentativeGridId())
				.as(locationKey)
				.isEqualTo(GridEncoder.encode(latLon[0], latLon[1])));
			assertThatCode(EventSeederTest.this::지연제약검증).doesNotThrowAnyException();
		}
	}
}
