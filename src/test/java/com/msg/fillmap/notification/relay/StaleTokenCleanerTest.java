package com.msg.fillmap.notification.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.notification.config.NotificationProperties;
import com.msg.fillmap.notification.repository.PushTokenRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 스테일 토큰 일 배치 검증 (MSG-179 D10, 실 PostGIS). 게이트(enabled) 컨텍스트 없이 직접 생성해
 * 판정 로직만 본다 — cron 배선은 게이트 컨텍스트 테스트 범위 밖(스케줄 등록은 스프링 몫)이고,
 * 기본 컨텍스트를 재사용해 스위트 컨텍스트 수를 늘리지 않는다.
 */
@SpringBootTest
@DisplayName("StaleTokenCleaner — last_used_at 무갱신 삭제 기준 (실 PostGIS)")
class StaleTokenCleanerTest {

	@Autowired
	private PushTokenRepository pushTokenRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private StaleTokenCleaner cleaner;
	private long me;
	private String token;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		cleaner = new StaleTokenCleaner(pushTokenRepository, properties(), txManager);
		token = "stale-" + System.nanoTime();
		tx.executeWithoutResult(status -> {
			me = userRepository.save(
				User.createLocalUser("stale-" + System.nanoTime() + "@example.com", "hash", "스테일테스터")).getId();
			pushTokenRepository.upsert(token, me, "WEB", null);
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :me").setParameter("me", me).executeUpdate());
	}

	// 검증: FR-NOTI-05
	@Test
	@DisplayName("60일 무갱신 토큰이 삭제된다 — threshold 는 앱 UTC 계산 (D10)")
	void 육십일_무갱신_토큰이_삭제된다() {
		setLastUsedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(61));

		cleaner.clean();

		assertThat(tokenCount()).isZero();
	}

	// 검증: FR-NOTI-05
	@Test
	@DisplayName("기준 이내 토큰은 남는다")
	void 기준_이내_토큰은_남는다() {
		setLastUsedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(59));

		cleaner.clean();

		assertThat(tokenCount()).isEqualTo(1);
	}

	/** 게이트 밖 직접 생성용 설정 — 기본값과 동일 형상 (stale-token-days 60). */
	private NotificationProperties properties() {
		return new NotificationProperties(false, "notification.send", "fillmap-notification",
			new NotificationProperties.Relay(5000L, 100, 30), new NotificationProperties.RateLimit(1, 1), 60,
			new NotificationProperties.Fcm(null));
	}

	private void setLastUsedAt(LocalDateTime lastUsedAt) {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("UPDATE push_tokens SET last_used_at = :lastUsedAt WHERE fcm_token = :token")
				.setParameter("lastUsedAt", lastUsedAt)
				.setParameter("token", token)
				.executeUpdate());
	}

	private long tokenCount() {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM push_tokens WHERE fcm_token = :token")
			.setParameter("token", token)
			.getSingleResult()).longValue();
	}
}
