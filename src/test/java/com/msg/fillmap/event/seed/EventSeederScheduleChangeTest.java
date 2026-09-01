package com.msg.fillmap.event.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventNotificationSubscriptionRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.event.service.EventNotificationScheduler;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 재시드의 일정 변경 알림과 종료 회차 구독 선삭제 (MSG-442, 실 PostgreSQL). 일정은 재시드로만 바뀌므로
 * 변경 알림의 트리거가 스케줄러가 아니라 시더다 — 시딩 트랜잭션 안이라 outbox 의 원자성 설계에 부합한다.
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg442d-*)로 커밋해 쓰고 {@code @AfterEach} 에서 지운다. 사용자 삭제가
 * notifications·구독 행을 CASCADE 로 걷어가고, 회차·위치·시리즈는 순서대로 지운다.
 */
@SpringBootTest
@DisplayName("EventSeeder 일정 변경 알림·종료 구독 선삭제 (실 PostgreSQL)")
class EventSeederScheduleChangeTest {

	/** 국내 실데이터와 겹치지 않는 합성 격자 대역 (EventSeederTest 선례). */
	private static final int Y = 90000;
	private static final int X = 90500;

	private static final String 시작_KST = "2026-10-06T10:00:00+09:00";
	private static final String 종료_KST = "2026-10-15T22:00:00+09:00";
	private static final String 연장_종료_KST = "2026-10-20T22:00:00+09:00";

	/** 회차가 진행 중인 시점 (UTC). 시드의 시작 10:00 KST = 01:00 UTC. */
	private static final LocalDateTime 진행중 = LocalDateTime.of(2026, 10, 7, 1, 0);
	/** 회차가 이미 끝난 시점 — 종료 22:00 KST = 13:00 UTC. */
	private static final LocalDateTime 종료후 = LocalDateTime.of(2026, 10, 16, 1, 0);
	/** 시작 직후 (발송 창 24시간 안) — 시작 알림 후보 술어가 잡는 지점. */
	private static final LocalDateTime 시작직후 = LocalDateTime.of(2026, 10, 6, 2, 0);
	/** 연기된 시작 — 시작직후 기준으로 아직 미래라 후보에서 빠져야 한다. */
	private static final String 연기_시작_KST = "2026-10-20T10:00:00+09:00";
	/** 위 연기 시작의 UTC 값 (10:00 KST = 01:00 UTC) — 새 시작 알림 키의 재료다. */
	private static final LocalDateTime 연기_시작_UTC = LocalDateTime.of(2026, 10, 20, 1, 0);
	/** 연기된 시작 직후 (발송 창 24시간 안, 연장 종료 13:00 UTC 이전). */
	private static final LocalDateTime 연기_시작_이후 = LocalDateTime.of(2026, 10, 20, 2, 0);

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
	private EventNotificationSubscriptionRepository subscriptionRepository;

	@Autowired
	private NotificationCommandService notificationCommandService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private String 시리즈키;
	private String 회차키;
	private long userId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		시리즈키 = "msg442d-" + UUID.randomUUID().toString().substring(0, 8);
		회차키 = 시리즈키 + "-occ";
		tx.executeWithoutResult(status -> userId = userRepository.save(User.createLocalUser(
			"msg442d-" + UUID.randomUUID() + "@example.com", "hash", "테스터")).getId());
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", userId).executeUpdate();
			em.createNativeQuery("""
				DELETE FROM event_locations WHERE event_occurrence_id IN
					(SELECT o.id FROM event_occurrences o JOIN event_series s ON s.id = o.event_series_id
					 WHERE s.series_key = :key)
				""").setParameter("key", 시리즈키).executeUpdate();
			em.createNativeQuery("""
				DELETE FROM event_occurrences WHERE event_series_id IN
					(SELECT id FROM event_series WHERE series_key = :key)
				""").setParameter("key", 시리즈키).executeUpdate();
			em.createNativeQuery("DELETE FROM event_series WHERE series_key = :key")
				.setParameter("key", 시리즈키).executeUpdate();
		});
	}

	/** 시더 한 번 실행 — 트랜잭션은 여기서 연다(운영의 {@code @Transactional} run() 과 같은 경계). */
	private void 시딩(LocalDateTime now, String startsAt, String endsAt) {
		tx.executeWithoutResult(status -> 시더(now).seed(시드(startsAt, endsAt)));
	}

	private EventSeeder 시더(LocalDateTime now) {
		return new EventSeeder(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository, eventVideoRepository, subscriptionRepository, notificationCommandService,
			objectMapper, Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	/**
	 * 다른 세션이 <b>이 세션 때문에</b> 막힐 때까지 기다린다 (최대 10초, 관찰되면 true).
	 * pg_blocking_pids 에 내 pid 가 있으면 "내가 잡은 잠금이 상대를 세우고 있다"가 확정된다
	 * (EventSeederDriftGuardTest 선례). 상대 pid 를 미리 알 수 없어 방향만 뒤집었고 판정은 같다 —
	 * 전역 대기 수를 세는 것이 아니라 여전히 "나에게 막힌" 세션만 본다.
	 */
	private boolean 틱이_나에게_막히기를_기다린다() {
		String sql = "SELECT EXISTS (SELECT 1 FROM pg_stat_activity a WHERE a.pid <> pg_backend_pid() "
			+ "AND pg_backend_pid() = ANY(pg_blocking_pids(a.pid)))";
		for (int attempt = 0; attempt < 200; attempt++) {
			if (Boolean.TRUE.equals(em.createNativeQuery(sql).getSingleResult())) {
				return true;
			}
			sleep();
		}
		return false;
	}

	private void sleep() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private Resource 시드(String startsAt, String endsAt) {
		EventSeed.Location location = new EventSeed.Location(시리즈키 + "-loc", "합성 장소", "ETC", null, 1,
			List.of(new EventSeed.Rect(Y, Y, X, X)), null, null);
		EventSeed.Occurrence occurrence = new EventSeed.Occurrence(회차키, "합성 회차", "부산", startsAt, endsAt,
			new EventSeed.Rect(Y - 10, Y + 10, X - 10, X + 10), null, List.of(location));
		return new ByteArrayResource(objectMapper.writeValueAsBytes(
			new EventSeed[] {new EventSeed(시리즈키, "합성 시리즈", List.of(occurrence))}));
	}

	private long 회차id() {
		return tx.execute(status -> occurrenceRepository.findByOccurrenceKey(회차키).orElseThrow().getId());
	}

	private int 개정번호() {
		return tx.execute(status -> occurrenceRepository.findByOccurrenceKey(회차키).orElseThrow()
			.getScheduleRevision());
	}

	private void 구독() {
		tx.executeWithoutResult(status -> subscriptionRepository.insertSubscription(userId, 회차id()));
	}

	private long 구독수() {
		return tx.execute(status -> (long) subscriptionRepository.findAllByIdEventOccurrenceId(회차id()).size());
	}

	@SuppressWarnings("unchecked")
	private List<String> 알림키() {
		return tx.execute(status -> (List<String>) em.createNativeQuery(
			"SELECT event_key FROM notifications WHERE user_id = :userId ORDER BY id")
			.setParameter("userId", userId).getResultList());
	}

	@Nested
	@DisplayName("일정 변경 알림")
	class ScheduleChange {

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("일정 변경 재시드는 개정 번호를 올리고 구독자에게 변경 알림을 기록한다")
		void 일정_변경_재시드는_개정_번호를_올리고_구독자에게_변경_알림을_기록한다() {
			시딩(진행중, 시작_KST, 종료_KST);
			구독();

			시딩(진행중, 시작_KST, 연장_종료_KST);

			assertThat(개정번호()).isEqualTo(1);
			assertThat(알림키()).containsExactly("EVENT_SCHEDULE:" + 회차키 + ":1");
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("일정이 같은 재시드는 개정 번호와 알림이 불변이다 — 첫 시딩도 변경이 아니다")
		void 일정이_같은_재시드는_개정_번호와_알림이_불변이다() {
			시딩(진행중, 시작_KST, 종료_KST);
			구독();

			시딩(진행중, 시작_KST, 종료_KST);

			assertThat(개정번호()).isZero();
			assertThat(알림키()).isEmpty();
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("일정을 되돌렸다 다시 바꾸는 왕복에서도 매 변경이 발송된다 — 키가 개정 번호라 억제되지 않는다")
		void 일정을_되돌렸다_다시_바꾸는_왕복에서도_매_변경이_발송된다() {
			시딩(진행중, 시작_KST, 종료_KST);
			구독();

			시딩(진행중, 시작_KST, 연장_종료_KST);
			시딩(진행중, 시작_KST, 종료_KST);
			시딩(진행중, 시작_KST, 연장_종료_KST);

			assertThat(개정번호()).isEqualTo(3);
			assertThat(알림키()).containsExactly(
				"EVENT_SCHEDULE:" + 회차키 + ":1",
				"EVENT_SCHEDULE:" + 회차키 + ":2",
				"EVENT_SCHEDULE:" + 회차키 + ":3");
		}
	}

	@Nested
	@DisplayName("종료 경계를 지난 회차의 재시드")
	class EndedOccurrence {

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("종료 경계를 지난 회차의 부활 연장은 기존 구독을 지우고 변경 알림을 보내지 않는다")
		void 종료_경계를_지난_회차의_부활_연장은_기존_구독을_지우고_변경_알림을_보내지_않는다() {
			시딩(진행중, 시작_KST, 종료_KST);
			구독();

			// 정리 틱이 아직 안 돈 창에서 종료 후 연장 — 선삭제가 없으면 구독이 되살아 알림까지 받는다
			시딩(종료후, 시작_KST, 연장_종료_KST);

			assertThat(구독수()).isZero();
			assertThat(알림키()).isEmpty();
			assertThat(개정번호()).isEqualTo(1);   // 개정 자체는 올랐고 수신자만 없다
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("종료 후 과거로 남는 일정 변경 재시드도 구독을 지우고 변경 알림을 보내지 않는다")
		void 종료_후_과거로_남는_일정_변경_재시드도_구독을_지우고_변경_알림을_보내지_않는다() {
			시딩(진행중, 시작_KST, 종료_KST);
			구독();

			// 새 종료도 과거 — 선삭제 조건이 새 값과 무관함을 본다
			시딩(종료후, 시작_KST, "2026-10-16T02:00:00+09:00");

			assertThat(구독수()).isZero();
			assertThat(알림키()).isEmpty();
		}
	}

	@Nested
	@DisplayName("첫 시딩")
	class FirstSeed {

		// 검증: FR-EVENT-10
		@Test
		@DisplayName("새 회차는 개정 0 으로 태어난다 — 존재하지 않던 일정의 변경 알림을 만들지 않는다")
		void 새_회차는_개정_0으로_태어난다() {
			시딩(진행중, 시작_KST, 종료_KST);

			assertThat(개정번호()).isZero();
			assertThat(occurrenceRepository.findByOccurrenceKey(회차키))
				.get()
				.extracting(EventOccurrence::getScheduleRevision)
				.isEqualTo(0);
		}
	}

	/**
	 * 시더와 스케줄러의 advisory lock 직렬화 (MSG-438 잠금 계약 테스트와 같은 형태). 두 트랜잭션을 실제로
	 * 겹쳐 돌린다 — 시더가 락을 쥔 채 재시드를 아직 커밋하지 않은 상태에서 틱을 띄우고, 틱이 그 락에
	 * 막히는 것을 {@code pg_blocking_pids} 로 관측한 뒤 시더를 커밋시킨다.
	 * <p>
	 * 두 테스트 모두 시더 쪽 시각을 구 ends_at 이전으로 둔다 — 종료 회차 선삭제가 발화하면 락과 무관하게
	 * 구독이 정당하게 사라져 경쟁 자체를 볼 수 없다.
	 */
	@Nested
	@DisplayName("advisory lock 직렬화")
	class Serialization {

		/**
		 * 정리 단계의 낡은 스냅숏 방어. 정리 시각은 <b>구</b> ends_at 을 지났고 <b>새</b> ends_at 은 안
		 * 지난 지점이라, 락이 없으면 정리의 DELETE 가 시더 커밋 전 스냅숏(구 ends_at)을 읽어 구독을
		 * 전멸시켜 아래 두 단언이 함께 깨진다.
		 */
		// 검증: FR-EVENT-06
		@Test
		@DisplayName("정리와 일정 연장 재시드가 경쟁해도 연장된 회차의 구독이 해제되지 않는다")
		void 정리와_일정_연장_재시드가_경쟁해도_연장된_회차의_구독이_해제되지_않는다() throws InterruptedException {
			시딩(진행중, 시작_KST, 종료_KST);
			구독();

			Thread 정리 = new Thread(() -> 스케줄러(false, 종료후).tick(), "msg442-cleanup");   // 발송 게이트 off
			Boolean 정리가_대기했다 = tx.execute(status -> {
				시더(진행중).seed(시드(시작_KST, 연장_종료_KST));   // 락 선취 + 연장 (아직 미커밋)
				em.flush();
				정리.start();
				return 틱이_나에게_막히기를_기다린다();
			});
			정리.join(15_000);

			assertThat(정리가_대기했다).as("정리 틱이 시더의 advisory lock 에서 실제로 대기해야 한다").isTrue();
			assertThat(구독수()).as("정리는 커밋된 새 ends_at 을 읽어 no-op 이어야 한다").isEqualTo(1);
		}

		/**
		 * 발송 단계의 낡은 스냅숏 방어. 락 없이 후보를 읽으면 그 스냅숏이 도는 사이 재시드가 시작을 미뤄도
		 * 옛 startsAt 키로 "행사가 시작됐어요"가 나가, 아직 시작하지 않은 행사의 알림이 된다.
		 * 발송 시각은 <b>구</b> starts_at 을 지났고(발송 창 24시간 안) <b>새</b> starts_at 은 안 지난
		 * 지점이라, 락이 없으면 알림이 1건 생겨 아래 단언이 깨진다.
		 */
		// 검증: FR-EVENT-06
		@Test
		@DisplayName("연기 재시드와 경쟁해도 연기된 회차의 시작 알림은 나가지 않는다")
		void 연기_재시드와_경쟁해도_연기된_회차의_시작_알림은_나가지_않는다() throws InterruptedException {
			시딩(시작직후, 시작_KST, 종료_KST);
			구독();

			Thread 발송 = new Thread(() -> 스케줄러(true, 시작직후).tick(), "msg442-start-fanout");
			Boolean 발송이_대기했다 = tx.execute(status -> {
				시더(시작직후).seed(시드(연기_시작_KST, 연장_종료_KST));   // 락 선취 + 시작 연기 (아직 미커밋)
				em.flush();
				발송.start();
				return 틱이_나에게_막히기를_기다린다();
			});
			발송.join(15_000);

			assertThat(발송이_대기했다).as("발송 틱이 시더의 advisory lock 에서 실제로 대기해야 한다").isTrue();
			// 연기 재시드 자체의 일정 변경 알림은 정상 발송이라 시작 알림 키만 골라 본다
			assertThat(알림키()).as("연기된 회차는 락 획득 후 후보 술어에서 빠져야 한다")
				.noneMatch(key -> key.startsWith("EVENT_START:"));

			// 부재를 존재로 승격 — 새 시작 시각이 오면 정확히 1건 나간다. 틱이 조용히 실패해 부재가 된
			// 것이라면 여기서도 안 나가므로, 두 단언이 함께 있어야 위 부재가 "억제"임이 확정된다.
			스케줄러(true, 연기_시작_이후).tick();

			assertThat(알림키()).containsExactly(
				"EVENT_SCHEDULE:" + 회차키 + ":1",
				"EVENT_START:" + 회차키 + ":" + 연기_시작_UTC.toEpochSecond(ZoneOffset.UTC));
		}

		private EventNotificationScheduler 스케줄러(boolean notificationEnabled, LocalDateTime now) {
			return new EventNotificationScheduler(occurrenceRepository, subscriptionRepository, seriesRepository,
				notificationCommandService, txManager, notificationEnabled,
				Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
		}
	}

}
