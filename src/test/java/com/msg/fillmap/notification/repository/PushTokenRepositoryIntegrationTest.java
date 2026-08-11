package com.msg.fillmap.notification.repository;

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

import com.msg.fillmap.notification.entity.PushToken;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * MSG-179 신설 3메서드(findAllByUserId·deleteAllByTokens·deleteStale) 통합 검증 (실 PostGIS).
 * ANY(배열 바인딩)·last_used_at 비교를 실제 DB 로 관찰 — 합성 유저 커밋 + @AfterEach 지정 삭제
 * (push_tokens 는 ON DELETE CASCADE — PushTokenServiceIntegrationTest 선례).
 */
@SpringBootTest
@DisplayName("PushTokenRepository 사용자 토큰 조회·시스템 삭제·스테일 삭제 (실 PostGIS)")
class PushTokenRepositoryIntegrationTest {

	@Autowired
	private PushTokenRepository pushTokenRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long me;
	private long other;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			me = newUser("repo-me");
			other = newUser("repo-other");
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id IN (:me, :other)")
				.setParameter("me", me)
				.setParameter("other", other)
				.executeUpdate());
	}

	@Test
	@DisplayName("findAllByUserId 는 해당 사용자 토큰만 돌려준다 — 컨슈머 발송 대상 조회")
	void findAllByUserId는_해당_사용자_토큰만_돌려준다() {
		String mine1 = register(me, "msg179-list-a-" + System.nanoTime());
		String mine2 = register(me, "msg179-list-b-" + System.nanoTime());
		register(other, "msg179-list-c-" + System.nanoTime());

		assertThat(pushTokenRepository.findAllByUserId(me))
			.extracting(PushToken::getFcmToken)
			.containsExactlyInAnyOrder(mine1, mine2);
	}

	// 검증: FR-NOTI-05
	@Test
	@DisplayName("deleteAllByTokens 는 소유자 무관하게 지정 토큰만 지운다 — FR-5 시스템 삭제 (의도된 무검증)")
	void deleteAllByTokens는_소유자_무관하게_지정_토큰만_지운다() {
		String mine = register(me, "msg179-del-a-" + System.nanoTime());
		String survivor = register(me, "msg179-del-b-" + System.nanoTime());
		String others = register(other, "msg179-del-c-" + System.nanoTime());

		tx.executeWithoutResult(status ->
			pushTokenRepository.deleteAllByTokens(new String[] {mine, others}));

		assertThat(pushTokenRepository.findById(mine)).isEmpty();
		assertThat(pushTokenRepository.findById(others)).isEmpty();   // 다른 사용자 소유도 삭제 — 무효 토큰 정리
		assertThat(pushTokenRepository.findById(survivor)).isPresent();
	}

	// 검증: FR-NOTI-05
	@Test
	@DisplayName("deleteStale 은 threshold 이전 last_used_at 행만 지운다 — D10 스테일 배치 쿼리")
	void deleteStale은_threshold_이전_행만_지운다() {
		String stale = register(me, "msg179-stale-" + System.nanoTime());
		String fresh = register(me, "msg179-fresh-" + System.nanoTime());
		tx.executeWithoutResult(status ->
			em.createNativeQuery("UPDATE push_tokens SET last_used_at = now() - interval '61 days' "
					+ "WHERE fcm_token = :token")
				.setParameter("token", stale)
				.executeUpdate());

		tx.executeWithoutResult(status ->
			pushTokenRepository.deleteStale(LocalDateTime.now(ZoneOffset.UTC).minusDays(60)));

		assertThat(pushTokenRepository.findById(stale)).isEmpty();
		assertThat(pushTokenRepository.findById(fresh)).isPresent();
	}

	private long newUser(String prefix) {
		String email = prefix + "-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "토큰리포테스터")).getId();
	}

	/** 기존 upsert 로 커밋 등록 — last_used_at 은 DB now() (세션 TZ 캐스트 저장이나 60일 판정엔 무해). */
	private String register(long userId, String token) {
		tx.executeWithoutResult(status -> pushTokenRepository.upsert(token, userId, "WEB", null));
		return token;
	}
}
