package com.msg.fillmap.event.service;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;
import com.msg.fillmap.event.repository.EventNotificationSubscriptionRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 생명주기 스케줄러 두 단계 (MSG-442, 실 PostgreSQL). 시작 알림 fanout 과 종료 구독 자동 해제를 고정
 * Clock 으로 재현한다 — 발송 창·created_at 조건·dedupe·advisory lock 이 전부 DB 동작이라 목으로는 못 잡는다.
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg442s-*)로 커밋해 쓰고 {@code @AfterEach} 에서 지운다. 사용자 삭제가
 * notifications·구독 행을 CASCADE 로 걷어가고, 회차·시리즈는 순서대로 지운다.
 */
@SpringBootTest
@DisplayName("EventNotificationScheduler 시작 알림·종료 해제 (실 PostgreSQL)")
class EventNotificationSchedulerTest {

	private static final LocalDateTime 시작 = LocalDateTime.of(2026, 10, 6, 1, 0);
	private static final LocalDateTime 종료 = LocalDateTime.of(2026, 10, 15, 13, 0);

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventNotificationSubscriptionRepository subscriptionRepository;

	@Autowired
	private NotificationCommandService notificationCommandService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long userId;
	private long seriesId;
	private long occurrenceId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			userId = userRepository.save(User.createLocalUser(
				"msg442s-" + UUID.randomUUID() + "@example.com", "hash", "테스터")).getId();
			EventSeries series = seriesRepository.save(new EventSeries("msg442s-" + 짧은키(), "합성 시리즈"));
			seriesId = series.getId();
			EventOccurrence occurrence = new EventOccurrence(series, "msg442s-occ-" + 짧은키());
			occurrence.update(series, "합성 회차", "부산", 시작, 종료, 90000, 90001, 90500, 90501);
			occurrenceId = occurrenceRepository.save(occurrence).getId();
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", userId).executeUpdate();
			em.createNativeQuery("DELETE FROM event_occurrences WHERE event_series_id = :id")
				.setParameter("id", seriesId).executeUpdate();
			em.createNativeQuery("DELETE FROM event_series WHERE id = :id").setParameter("id", seriesId)
				.executeUpdate();
		});
	}

	/** 자연키 컬럼 폭(series_key 50자)에 맞춘 합성 접미사. */
	private String 짧은키() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private EventNotificationScheduler 스케줄러(boolean notificationEnabled, LocalDateTime now) {
		return new EventNotificationScheduler(occurrenceRepository, subscriptionRepository, seriesRepository,
			notificationCommandService, txManager, notificationEnabled,
			Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	/** 구독 행을 만들면서 created_at 을 원하는 시각으로 고정한다 — 발송 대상 판정 재료라 값이 검증에 든다. */
	private void 구독(LocalDateTime createdAt) {
		tx.executeWithoutResult(status -> {
			subscriptionRepository.insertSubscription(userId, occurrenceId);
			em.createNativeQuery("""
				UPDATE event_notification_subscriptions SET created_at = :createdAt
				WHERE user_id = :userId AND event_occurrence_id = :occurrenceId
				""")
				.setParameter("createdAt", createdAt)
				.setParameter("userId", userId)
				.setParameter("occurrenceId", occurrenceId)
				.executeUpdate();
		});
	}

	private long 알림수() {
		return tx.execute(status -> ((Number) em.createNativeQuery(
			"SELECT count(*) FROM notifications WHERE user_id = :userId AND category = 'EVENT'")
			.setParameter("userId", userId).getSingleResult()).longValue());
	}

	@SuppressWarnings("unchecked")
	private List<String> 알림키() {
		return tx.execute(status -> (List<String>) em.createNativeQuery(
			"SELECT event_key FROM notifications WHERE user_id = :userId ORDER BY id")
			.setParameter("userId", userId).getResultList());
	}

	@Nested
	@DisplayName("시작 알림")
	class StartNotification {

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("시작 시각이 지난 회차의 구독자에게 시작 알림이 기록된다")
		void 시작_시각이_지난_회차의_구독자에게_시작_알림이_기록된다() {
			구독(시작.minusDays(1));

			스케줄러(true, 시작.plusMinutes(1)).tick();

			assertThat(알림수()).isEqualTo(1);
			assertThat(알림키()).containsExactly(
				"EVENT_START:" + 회차키() + ":" + 시작.toEpochSecond(ZoneOffset.UTC));
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("스케줄러를 두 번 돌려도 시작 알림은 한 건이다 — outbox UNIQUE 가 재기록을 흡수")
		void 스케줄러를_두_번_돌려도_시작_알림은_한_건이다() {
			구독(시작.minusDays(1));

			스케줄러(true, 시작.plusMinutes(1)).tick();
			스케줄러(true, 시작.plusHours(2)).tick();

			assertThat(알림수()).isEqualTo(1);
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("시작 후에 구독한 사용자는 시작 알림을 받지 않는다 — 진행 중임을 알고 눌렀다")
		void 시작_후에_구독한_사용자는_시작_알림을_받지_않는다() {
			구독(시작.plusMinutes(1));

			스케줄러(true, 시작.plusHours(1)).tick();

			assertThat(알림수()).isZero();
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("발송 창 24시간이 지난 회차는 스캔되지 않는다 — 매 틱 전 구독자 재훑기 방지")
		void 발송_창_24시간이_지난_회차는_스캔되지_않는다() {
			구독(시작.minusDays(1));

			스케줄러(true, 시작.plusHours(24)).tick();

			assertThat(알림수()).isZero();
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("일정 변경 후 새 시작 시각에 시작 알림이 다시 나간다 — 키가 일정 버전 단위다")
		void 일정_변경_후_새_시작_시각에_시작_알림이_다시_나간다() {
			구독(시작.minusDays(10));
			스케줄러(true, 시작.plusMinutes(1)).tick();
			LocalDateTime 새시작 = 시작.plusDays(3);
			tx.executeWithoutResult(status -> {
				EventOccurrence occurrence = occurrenceRepository.findById(occurrenceId).orElseThrow();
				occurrence.update(occurrence.getSeries(), "합성 회차", "부산", 새시작, 종료,
					90000, 90001, 90500, 90501);
			});

			스케줄러(true, 새시작.plusMinutes(1)).tick();

			assertThat(알림수()).isEqualTo(2);
		}

		private String 회차키() {
			return tx.execute(status -> occurrenceRepository.findById(occurrenceId).orElseThrow()
				.getOccurrenceKey());
		}
	}

	@Nested
	@DisplayName("종료 구독 해제")
	class Release {

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("종료 시각이 지나면 구독이 자동 해제되고 재실행해도 안전하다")
		void 종료_시각이_지나면_구독이_자동_해제되고_재실행해도_안전하다() {
			구독(시작.minusDays(1));

			스케줄러(true, 종료).tick();
			스케줄러(true, 종료.plusDays(1)).tick();

			assertThat(subscriptionRepository.findAllByIdEventOccurrenceId(occurrenceId)).isEmpty();
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("종료 정각 전에는 해제하지 않는다 — 반개구간 경계")
		void 종료_정각_전에는_해제하지_않는다() {
			구독(시작.minusDays(1));

			// 1마이크로초 전 — PostgreSQL timestamp 정밀도가 마이크로초라 1나노초 전은 반올림돼 정각이 된다
			스케줄러(true, 종료.minusNanos(1_000)).tick();

			assertThat(subscriptionRepository.findAllByIdEventOccurrenceId(occurrenceId)).hasSize(1);
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("알림 발송이 꺼진 환경에서도 종료 구독 해제는 돈다 — 정리는 발송 게이트 밖이라 행이 안 쌓인다")
		void 알림_발송이_꺼진_환경에서도_종료_구독_해제는_돈다() {
			구독(시작.minusDays(1));

			스케줄러(false, 종료.plusDays(1)).tick();

			assertThat(subscriptionRepository.findAllByIdEventOccurrenceId(occurrenceId)).isEmpty();
			assertThat(알림수()).isZero();   // 발송 단계는 게이트에 걸려 아예 돌지 않았다
		}
	}
}
