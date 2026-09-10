package com.msg.fillmap.event.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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

import com.msg.fillmap.event.entity.EventNotificationSubscriptionId;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 행사 알림 구독 저장소와 V40 DDL (실 PostgreSQL, MSG-442). 토글 두 native 문장의 멱등, 연쇄 삭제,
 * created_at DEFAULT 의 세션 타임존 무관성을 본다.
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg442-*)로 커밋해 쓰고 {@code @AfterEach} 에서 지운다 — 여기서
 * 커밋이 필요한 이유는 컬럼 DEFAULT 검증이 KST 세션 트랜잭션을 따로 열기 때문이다.
 */
@SpringBootTest
@DisplayName("EventNotificationSubscription 저장소 · V40 DDL (실 PostgreSQL)")
class EventNotificationSubscriptionRepositoryTest {

	/** 정상이면 DB 가 같은 문장에서 만든 두 값의 차라 1초 미만. KST 스큐(32400초)와 구분되기만 하면 된다. */
	private static final double 허용_오차초 = 5.0;

	private static final LocalDateTime 시작 = LocalDateTime.of(2026, 10, 6, 1, 0);
	private static final LocalDateTime 종료 = LocalDateTime.of(2026, 10, 15, 13, 0);

	@Autowired
	private EventNotificationSubscriptionRepository subscriptionRepository;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

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
				"msg442-" + UUID.randomUUID() + "@example.com", "hash", "테스터")).getId();
			EventSeries series = seriesRepository.save(
				new EventSeries("msg442-series-" + 짧은키(), "합성 시리즈"));
			seriesId = series.getId();
			EventOccurrence occurrence = new EventOccurrence(series, "msg442-occ-" + 짧은키());
			occurrence.update(series, "합성 회차", "부산", 시작, 종료, 90000, 90001, 90500, 90501);
			occurrenceId = occurrenceRepository.save(occurrence).getId();
		});
	}

	/** 자연키 컬럼 폭(series_key 50자)에 맞춘 합성 접미사. */
	private String 짧은키() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", userId).executeUpdate();
			// 회차 → 시리즈 순서 — event_occurrences 의 FK 에는 ON DELETE 가 없어 자식이 먼저 사라져야 한다
			em.createNativeQuery("DELETE FROM event_occurrences WHERE event_series_id = :id")
				.setParameter("id", seriesId).executeUpdate();
			em.createNativeQuery("DELETE FROM event_series WHERE id = :id").setParameter("id", seriesId)
				.executeUpdate();
		});
	}

	@Nested
	@DisplayName("토글 멱등")
	class Toggle {

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("구독 ON 반복은 행 하나를 유지하고 두 번째는 0행이다 (ON CONFLICT DO NOTHING)")
		void 구독_ON_반복은_행_하나를_유지한다() {
			int first = tx.execute(status -> subscriptionRepository.insertSubscription(userId, occurrenceId));
			int second = tx.execute(status -> subscriptionRepository.insertSubscription(userId, occurrenceId));

			assertThat(first).isEqualTo(1);
			assertThat(second).isZero();
			assertThat(subscriptionRepository.existsById(
				new EventNotificationSubscriptionId(userId, occurrenceId))).isTrue();
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("구독 OFF 는 행이 없어도 0행으로 성공한다")
		void 구독_OFF는_행이_없어도_0행으로_성공한다() {
			tx.executeWithoutResult(status -> subscriptionRepository.insertSubscription(userId, occurrenceId));

			int first = tx.execute(status -> subscriptionRepository.deleteSubscription(userId, occurrenceId));
			int second = tx.execute(status -> subscriptionRepository.deleteSubscription(userId, occurrenceId));

			assertThat(first).isEqualTo(1);
			assertThat(second).isZero();
			assertThat(subscriptionRepository.existsById(
				new EventNotificationSubscriptionId(userId, occurrenceId))).isFalse();
		}
	}

	@Nested
	@DisplayName("V40 DDL")
	class Ddl {

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("구독 행은 회차 삭제와 사용자 삭제에 연쇄 제거된다 (ON DELETE CASCADE 양방향)")
		void 구독_행은_회차_삭제와_사용자_삭제에_연쇄_제거된다() {
			tx.executeWithoutResult(status -> subscriptionRepository.insertSubscription(userId, occurrenceId));

			tx.executeWithoutResult(status ->
				em.createNativeQuery("DELETE FROM event_occurrences WHERE id = :id")
					.setParameter("id", occurrenceId).executeUpdate());

			assertThat(subscriptionRepository.existsById(
				new EventNotificationSubscriptionId(userId, occurrenceId))).isFalse();
		}

		@Test
		@DisplayName("구독 행의 created_at 은 세션 타임존과 무관하게 UTC 로 저장된다 (V33 선례 DEFAULT)")
		void 구독_행의_created_at은_세션_타임존과_무관하게_UTC로_저장된다() {
			tx.executeWithoutResult(status -> {
				em.createNativeQuery("SET LOCAL TIME ZONE 'Asia/Seoul'").executeUpdate();
				subscriptionRepository.insertSubscription(userId, occurrenceId);
			});

			double skew = tx.execute(status -> ((Number) em.createNativeQuery("""
				SELECT abs(EXTRACT(EPOCH FROM (created_at - (statement_timestamp() AT TIME ZONE 'utc'))))
				FROM event_notification_subscriptions
				WHERE user_id = :userId AND event_occurrence_id = :occurrenceId
				""")
				.setParameter("userId", userId)
				.setParameter("occurrenceId", occurrenceId)
				.getSingleResult()).doubleValue());

			assertThat(skew).isLessThan(허용_오차초);
		}
	}

	@Nested
	@DisplayName("일정 개정 번호")
	class ScheduleRevision {

		// 검증: FR-EVENT-10
		@Test
		@DisplayName("시각이 바뀐 갱신만 개정 번호를 올린다 — 같은 값 재갱신은 불변")
		void 시각이_바뀐_갱신만_개정_번호를_올린다() {
			tx.executeWithoutResult(status -> {
				EventOccurrence occurrence = occurrenceRepository.findById(occurrenceId).orElseThrow();
				assertThat(occurrence.getScheduleRevision()).isZero();   // 첫 세팅은 변경이 아니다

				occurrence.update(occurrence.getSeries(), "합성 회차", "부산", 시작, 종료, 90000, 90001, 90500, 90501);
				assertThat(occurrence.getScheduleRevision()).isZero();

				occurrence.update(occurrence.getSeries(), "합성 회차", "부산", 시작, 종료.plusDays(1),
					90000, 90001, 90500, 90501);
				assertThat(occurrence.getScheduleRevision()).isEqualTo(1);
			});

			assertThat(occurrenceRepository.findById(occurrenceId).orElseThrow().getScheduleRevision())
				.isEqualTo(1);
		}
	}
}
