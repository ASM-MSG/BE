package com.msg.fillmap.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.user.entity.User;

/**
 * 아이디 변경 요청의 동시 접수 (MSG-497 §6, 실 DB). 부분 유니크 인덱스
 * (uq_org_email_change_requests_pending) 위에서 두 요청이 겹쳐도 UPSERT 한 문장이 한 행으로 수렴하는지를
 * 본다 — 조회 후 INSERT 로 갈랐다면 여기서 제약 위반 500 이 난다.
 *
 * <p>진짜 동시 트랜잭션이 필요해 클래스 수준 {@code @Transactional} 롤백 격리를 쓸 수 없다(두 스레드가
 * 같은 트랜잭션을 공유해 경쟁이 재현되지 않는다). 대신 사용자 행을 직접 지워 공유 로컬 DB 를 되돌린다 —
 * 요청 행은 FK ON DELETE CASCADE 로 함께 사라진다.
 */
@SpringBootTest
@DisplayName("아이디 변경 요청 동시 접수 (실 DB)")
class OrgEmailChangeConcurrencyTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrgEmailChangeRequestRepository requestRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private Long userId;

	@BeforeEach
	void setUp() {
		userId = transactionTemplate.execute(status -> userRepository.save(
			User.createLocalUser("org-" + UUID.randomUUID() + "@fillmap.dev", "{noop}pw", "담당자")).getId());
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> userRepository.deleteUser(userId));
	}

	// 검증: FR-USER-16
	@Test
	@DisplayName("동시 요청 두 건이 모두 성공하고 행은 하나다 — ON CONFLICT 경쟁")
	void 동시_요청_두_건이_모두_성공하고_행은_하나다() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			Future<?> first = executor.submit(() -> 접수한다(barrier, "first@fillmap.dev"));
			Future<?> second = executor.submit(() -> 접수한다(barrier, "second@fillmap.dev"));

			assertThatCode(() -> {
				first.get();
				second.get();
			}).doesNotThrowAnyException();

			assertThat(requestRepository.findAllByUserId(userId)).hasSize(1);
		} finally {
			executor.shutdownNow();
		}
	}

	private Object 접수한다(CyclicBarrier barrier, String email) throws Exception {
		barrier.await();
		transactionTemplate.executeWithoutResult(status ->
			requestRepository.upsertPending(userId, email, LocalDateTime.now(ZoneOffset.UTC)));
		return null;
	}
}
