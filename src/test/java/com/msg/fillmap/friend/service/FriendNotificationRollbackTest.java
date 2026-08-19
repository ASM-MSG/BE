package com.msg.fillmap.friend.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 친구 요청 → FRIEND 알림 원자성 통합 검증 (실 PostGIS, MSG-416 FR-5). 롤백 동반 소멸은 커밋 경계
 * 관찰이 필요해 FriendIntegrationTest 의 @Transactional 격리(전 호출이 테스트 트랜잭션에 참여)로는
 * 검증할 수 없다 — 합성 유저를 커밋해 두고 요청 트랜잭션만 rollbackOnly 로 되돌린다
 * (BadgeNotificationIntegrationTest 선례). @AfterEach 의 users 지정 삭제가 friendships·notifications
 * 를 ON DELETE CASCADE 로 함께 걷는다.
 */
@SpringBootTest
@DisplayName("친구 요청 롤백 → FRIEND 알림 동반 소멸 (실 PostGIS)")
class FriendNotificationRollbackTest {

	@Autowired
	private FriendService friendService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	/** 실 S3 호출 차단용 — 컨텍스트의 UserServiceImpl(계정 삭제 축)이 주입받는 의존성, 이 테스트에선 미사용. */
	@MockitoBean
	private S3Client s3Client;

	private TransactionTemplate tx;
	private long meId;
	private long otherId;
	private String otherCode;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			meId = userRepository.save(User.createLocalUser(
				"friend-rollback-me-" + System.nanoTime() + "@example.com", "hash", "롤백테스터")).getId();
			User other = userRepository.save(User.createLocalUser(
				"friend-rollback-other-" + System.nanoTime() + "@example.com", "hash", "상대방"));
			otherId = other.getId();
			otherCode = other.getFriendCode();
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id IN (:me, :other)")
				.setParameter("me", meId)
				.setParameter("other", otherId)
				.executeUpdate());
	}

	// 검증: FR-NOTI-14
	@Test
	@DisplayName("요청 트랜잭션이 롤백되면 알림도 남지 않는다 — 전파 REQUIRED 원자성 실증 (FR-5)")
	void 요청_트랜잭션이_롤백되면_알림도_남지_않는다() {
		tx.executeWithoutResult(status -> {
			friendService.request(meId, otherCode);
			status.setRollbackOnly();
		});

		// REQUIRES_NEW 였다면 자체 커밋으로 알림만 살아남는다 — 관계·알림 모두 0행이어야 같은 커밋 증명.
		assertThat(friendshipCount()).isZero();
		assertThat(notificationCount()).isZero();
	}

	private long friendshipCount() {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM friendships WHERE requester_id = :meId")
			.setParameter("meId", meId)
			.getSingleResult()).longValue();
	}

	private long notificationCount() {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM notifications WHERE user_id = :otherId")
			.setParameter("otherId", otherId)
			.getSingleResult()).longValue();
	}
}
