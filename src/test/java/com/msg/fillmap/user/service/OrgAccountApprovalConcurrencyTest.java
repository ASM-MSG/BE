package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.OrgAccountIssueResponseDto;
import com.msg.fillmap.user.dto.OrgAccountRequestApproveRequestDto;
import com.msg.fillmap.user.entity.OrgAccountRequest;
import com.msg.fillmap.user.entity.OrgAccountRequestStatus;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.OrgAccountRequestRepository;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 같은 요청의 동시 승인 (MSG-499 API 4, 실 DB). 요청 행 잠금이 두 승인을 직렬화해 계정이 한 번만
 * 만들어지는지를 본다 — 잠금 없이 상태만 확인했다면 두 트랜잭션이 같은 PENDING 을 보고 계정을 둘 만들고
 * 늦은 쪽이 이메일 UNIQUE 위반 500 으로 끝난다.
 *
 * <p>진짜 동시 트랜잭션이 필요해 클래스 수준 {@code @Transactional} 롤백 격리를 쓸 수 없다
 * (OrgEmailChangeConcurrencyTest 와 같은 이유). 대신 만들어진 행을 직접 지워 공유 로컬 DB 를 되돌린다.
 */
@SpringBootTest
@DisplayName("계정 발급 요청 동시 승인 (실 DB)")
class OrgAccountApprovalConcurrencyTest {

	@Autowired
	private OrgAccountRequestService orgAccountRequestService;

	@Autowired
	private OrgAccountRequestRepository requestRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private String email;
	private Long requestId;
	private LocalDateTime reviewedAt;

	@BeforeEach
	void setUp() {
		email = "concurrent-" + UUID.randomUUID() + "@fillmap.dev";
		reviewedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		requestId = transactionTemplate.execute(status -> {
			requestRepository.upsertPending("부산진구청", "김담당", "010-1234-5678", email,
				"서면 겨울 축제", "계정을 신청합니다", reviewedAt);
			return null;
		});
		requestId = 요청을_찾는다().getId();
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> {
			// 요청 행을 먼저 지우고 flush 한다 — issued_user_id FK 가 살아 있으면 계정 삭제가 제약 위반이다.
			requestRepository.deleteById(requestId);
			requestRepository.flush();
			userRepository.findByEmail(email).ifPresent(user -> userRepository.deleteUser(user.getId()));
		});
	}

	private OrgAccountRequest 요청을_찾는다() {
		return transactionTemplate.execute(status -> requestRepository.findAll().stream()
			.filter(request -> email.equals(request.getEmail()))
			.findFirst()
			.orElseThrow());
	}

	// 검증: FR-AUTH-13
	@Test
	@DisplayName("동시 승인의 늦은 쪽은 1422 이고 계정은 하나만 만들어진다")
	void 동시_승인의_늦은_쪽은_1422다() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			Future<OrgAccountIssueResponseDto> first = executor.submit(() -> 승인한다(barrier));
			Future<OrgAccountIssueResponseDto> second = executor.submit(() -> 승인한다(barrier));

			List<Throwable> failures = Stream.of(결과를_받는다(first), 결과를_받는다(second))
				.filter(Objects::nonNull)
				.toList();

			assertThat(failures).hasSize(1).first().isInstanceOf(ApiException.class);
			assertThat(((ApiException) failures.getFirst()).getErrorCode())
				.isEqualTo(UserErrorCode.ORG_ACCOUNT_REQUEST_ALREADY_PROCESSED);
			assertThat(요청을_찾는다().getStatus()).isEqualTo(OrgAccountRequestStatus.ISSUED);
			assertThat(userRepository.findByEmail(email)).isPresent();
		} finally {
			executor.shutdownNow();
		}
	}

	private OrgAccountIssueResponseDto 승인한다(CyclicBarrier barrier) throws Exception {
		barrier.await();
		return orgAccountRequestService.approve(requestId, new OrgAccountRequestApproveRequestDto(reviewedAt));
	}

	/** 성공이면 null, 실패면 그 예외 — 어느 쪽이 이겼는지는 스케줄에 달려 있어 결과로 가른다. */
	private Throwable 결과를_받는다(Future<OrgAccountIssueResponseDto> future) throws InterruptedException {
		try {
			future.get();
			return null;
		} catch (ExecutionException e) {
			return e.getCause();
		}
	}
}
