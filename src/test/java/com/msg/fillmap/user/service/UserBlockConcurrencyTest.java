package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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
 * 같은 두 사용자의 차단 요청이 서로 다른 트랜잭션으로 동시에 들어오는 경우 (MSG-569 AC-02). 관측 대상이
 * "다른 커넥션이 커밋한 행"이라 실제 커밋과 두 스레드가 필요하다(EventVideoHelpfulConcurrencyTest 구조).
 * 격리(공유 로컬 DB): 실제 커밋을 하므로 @AfterEach 에서 이 테스트가 만든 id 만 FK 역순으로 지운다.
 */
@SpringBootTest
@DisplayName("사용자 차단 동시성 (실 PostgreSQL)")
class UserBlockConcurrencyTest {

	private static final long JOIN_TIMEOUT_MS = 15_000L;

	@Autowired
	private UserBlockService userBlockService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager transactionManager;

	/** 실 S3 호출 차단용 — 컨텍스트의 UserServiceImpl(계정 삭제 축)이 주입받는 의존성, 이 테스트에선 미사용. */
	@MockitoBean
	private S3Client s3Client;

	private TransactionTemplate tx;
	private Long blockerId;
	private Long blockedId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		tx.executeWithoutResult(status -> {
			blockerId = seed("차단자").getId();
			blockedId = seed("상대방").getId();
		});
	}

	private User seed(String nickname) {
		return userRepository.save(
			User.createLocalUser("block-race-" + UUID.randomUUID() + "@example.com", "hash", nickname));
	}

	/** users 삭제가 user_blocks 를 CASCADE 로 함께 지운다 — 그래도 명시 순서로 남긴다(실데이터 불가침). */
	@AfterEach
	void 정리() {
		tx.executeWithoutResult(status -> List.of(
			"DELETE FROM user_blocks WHERE blocker_id = " + blockerId,
			"DELETE FROM users WHERE id IN (" + blockerId + ", " + blockedId + ")"
		).forEach(sql -> em.createNativeQuery(sql).executeUpdate()));
	}

	// 검증: FR-MOD-15, AC-569-02
	@Test
	@DisplayName("같은 차단 요청이 동시에 들어와도 한 건만 남는다")
	void 같은_차단_요청이_동시에_들어와도_한_건만_남는다() throws InterruptedException {
		CountDownLatch 출발 = new CountDownLatch(1);
		AtomicReference<Exception> 왼쪽 = new AtomicReference<>();
		AtomicReference<Exception> 오른쪽 = new AtomicReference<>();
		Thread a = 스레드(출발, 왼쪽, "msg569-race-a");
		Thread b = 스레드(출발, 오른쪽, "msg569-race-b");
		a.start();
		b.start();
		출발.countDown();
		a.join(JOIN_TIMEOUT_MS);
		b.join(JOIN_TIMEOUT_MS);

		// 복합 PK 가 유일성을, ON CONFLICT DO NOTHING 이 늦은 쪽의 중복 키 예외를 각각 막는다 — 둘 다 200 이다.
		assertThat(Stream.of(왼쪽.get(), 오른쪽.get()).filter(Objects::nonNull).toList()).isEmpty();
		Number count = (Number) em.createNativeQuery(
				"SELECT COUNT(*) FROM user_blocks WHERE blocker_id = :a AND blocked_id = :b")
			.setParameter("a", blockerId).setParameter("b", blockedId).getSingleResult();
		assertThat(count.longValue()).isEqualTo(1);
	}

	private Thread 스레드(CountDownLatch 출발, AtomicReference<Exception> 예외, String 이름) {
		return new Thread(() -> {
			try {
				출발.await();
				userBlockService.block(blockerId, blockedId);
			} catch (Exception e) {
				예외.set(e);
			}
		}, 이름);
	}
}
