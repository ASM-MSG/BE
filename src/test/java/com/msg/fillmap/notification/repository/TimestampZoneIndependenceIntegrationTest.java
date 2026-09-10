package com.msg.fillmap.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 시각 저장이 세션 타임존에 좌우되지 않는지 본다 (MSG-379 D4·D8, 실 PostgreSQL).
 *
 * 타임존 없는 TIMESTAMP 컬럼에는 UTC 벽시계만 저장한다는 규약(MSG-376)인데, now()/CURRENT_TIMESTAMP 는
 * 타임존이 붙은 값이라 대입 시점에 세션 타임존으로 변환된다. UTC 세션에서 UTC 현재와 비교하는 테스트는
 * 옛 코드로도 통과해 무력하므로, {@code SET LOCAL TIME ZONE 'Asia/Seoul'} 로 세션을 KST 로 강제한 채
 * 쓰기를 실행한다 (PopupMissionSeederIntegrationTest 선례. SET LOCAL 은 그 트랜잭션 한정).
 * 교정 전이면 저장값이 9시간(32400초) 앞서 어긋나므로 실패한다.
 *
 * 오차는 SQL 안에서 재서 JVM 기본 존 변환이 끼어들 여지를 없앤다 — 두 피연산자 모두 DB 가 만든 값이라
 * 정상이면 1초 미만이다.
 *
 * 격리(공유 로컬 DB): 합성 사용자 1명을 커밋해 쓰고 {@code @AfterEach} 에서 지운다 —
 * push_tokens·notification_opt_outs 둘 다 ON DELETE CASCADE 라 같이 사라진다
 * (PushTokenRepositoryIntegrationTest 선례).
 */
@SpringBootTest
@DisplayName("시각 저장의 세션 타임존 무관성 (실 PostgreSQL) — KST 세션 강제")
class TimestampZoneIndependenceIntegrationTest {

	/** 정상이면 DB 가 같은 문장에서 만든 두 값의 차라 1초 미만. KST 스큐(32400초)와 구분되기만 하면 된다. */
	private static final double 허용_오차초 = 5.0;

	@Autowired
	private PushTokenRepository pushTokenRepository;

	@Autowired
	private NotificationOptOutRepository notificationOptOutRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long userId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> userId = userRepository.save(
			User.createLocalUser("msg379-" + System.nanoTime() + "@example.com", "hash", "존테스터")).getId());
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :id")
				.setParameter("id", userId)
				.executeUpdate());
	}

	// D4 — PushTokenRepository.upsert 의 시각 식 (statement_timestamp() AT TIME ZONE 'UTC')
	@Test
	@DisplayName("토큰 신규 등록 시각이 KST 세션에서도 UTC 벽시계로 저장된다 (D4 — INSERT 분기)")
	void 토큰_신규_등록_시각이_KST_세션에서도_UTC_벽시계로_저장된다() {
		String token = "msg379-insert-" + System.nanoTime();

		upsertInKstSession(token);

		assertThat(pushTokenSkewSeconds(token)).isLessThan(허용_오차초);
	}

	// D4 — 충돌 갱신도 같은 식(EXCLUDED.last_used_at)을 쓰므로 같이 확인한다
	@Test
	@DisplayName("토큰 재등록 시각도 KST 세션에서 UTC 벽시계로 갱신된다 (D4 — ON CONFLICT UPDATE 분기)")
	void 토큰_재등록_시각도_KST_세션에서_UTC_벽시계로_갱신된다() {
		String token = "msg379-conflict-" + System.nanoTime();
		tx.executeWithoutResult(status -> pushTokenRepository.upsert(token, userId, "WEB", null));
		// 방금 넣은 시각을 그대로 두면 ON CONFLICT 가 갱신을 아예 안 해도 통과한다 — 하루 전으로 밀어
		// 두고, 재등록이 값을 현재로 끌어올렸는지까지 봐야 갱신 사실과 그 값의 존이 함께 검증된다.
		tx.executeWithoutResult(status ->
			em.createNativeQuery("UPDATE push_tokens SET last_used_at = last_used_at - interval '1 day' "
					+ "WHERE fcm_token = :token")
				.setParameter("token", token)
				.executeUpdate());
		assertThat(pushTokenSkewSeconds(token)).isGreaterThan(허용_오차초);   // 백데이트 선반영 확인

		upsertInKstSession(token);   // 같은 토큰 재등록 = ON CONFLICT DO UPDATE 경로

		assertThat(pushTokenSkewSeconds(token)).isLessThan(허용_오차초);
	}

	// D8 — INSERT 가 시각 컬럼을 생략해 컬럼 DEFAULT 에 맡기는 경로 (V33 이 바꾼 식의 대표 검증)
	@Test
	@DisplayName("컬럼 DEFAULT 로 채워지는 시각도 KST 세션에서 UTC 벽시계로 저장된다 (D8 — V33)")
	void 컬럼_DEFAULT로_채워지는_시각도_KST_세션에서_UTC_벽시계로_저장된다() {
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("SET LOCAL TIME ZONE 'Asia/Seoul'").executeUpdate();
			notificationOptOutRepository.insertOptOut(userId, "BADGE");   // created_at 미지정 = DEFAULT 위임
		});

		double skew = tx.execute(status -> ((Number) em.createNativeQuery("""
			SELECT abs(EXTRACT(EPOCH FROM (created_at - (statement_timestamp() AT TIME ZONE 'utc'))))
			FROM notification_opt_outs WHERE user_id = :userId AND category = 'BADGE'
			""").setParameter("userId", userId).getSingleResult()).doubleValue());

		assertThat(skew).isLessThan(허용_오차초);
	}

	private void upsertInKstSession(String token) {
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("SET LOCAL TIME ZONE 'Asia/Seoul'").executeUpdate();
			pushTokenRepository.upsert(token, userId, "WEB", null);
		});
	}

	/** 저장된 last_used_at 과 실제 UTC 현재의 차이(초). 비교를 SQL 안에서 끝내 JVM 존 변환을 배제한다. */
	private double pushTokenSkewSeconds(String token) {
		return tx.execute(status -> ((Number) em.createNativeQuery("""
			SELECT abs(EXTRACT(EPOCH FROM (last_used_at - (statement_timestamp() AT TIME ZONE 'utc'))))
			FROM push_tokens WHERE fcm_token = :token
			""").setParameter("token", token).getSingleResult()).doubleValue());
	}
}
