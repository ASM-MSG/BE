package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.dto.EmailChangeApproveRequestDto;
import com.msg.fillmap.user.dto.EmailChangeApproveResponseDto;
import com.msg.fillmap.user.entity.OrgEmailChangeRequest;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.OrgEmailChangeRequestRepository;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 아이디 변경 승인의 커밋 경계 (MSG-500 D-13, 실 DB). 두 가지를 본다: 같은 이메일을 노린 동시 승인이
 * <b>읽히는 1409</b> 로 끝나는지(유니크 위반 500 이 아니라), 그리고 승인이 <b>email 컬럼만</b> 갱신해 다른
 * 트랜잭션이 방금 커밋한 값을 되돌리지 않는지다.
 *
 * <p>진짜 커밋이 필요해 클래스 수준 {@code @Transactional} 롤백 격리를 쓸 수 없다 — 동시 승인은 두
 * 트랜잭션이 실제로 경합해야 하고, 컬럼 보존은 "다른 트랜잭션이 먼저 커밋한 상태"가 전제다
 * (OrgAccountApprovalConcurrencyTest 와 같은 이유). 대신 만든 행을 직접 지워 공유 로컬 DB 를 되돌린다 —
 * 요청 행은 FK ON DELETE CASCADE 로 계정과 함께 사라진다.
 */
@SpringBootTest
@DisplayName("아이디 변경 승인 동시성과 컬럼 보존 (실 DB)")
class EmailChangeApprovalConcurrencyTest {

	@Autowired
	private AdminEmailChangeRequestService adminEmailChangeRequestService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrgEmailChangeRequestRepository requestRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	/** 통지 발송을 갈아 끼운다 — 이 클래스가 보는 것은 발송이 아니라 커밋 경계다. */
	@MockitoBean
	private MailSender mailSender;

	private final List<Long> 정리할_계정 = new ArrayList<>();

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status ->
			정리할_계정.forEach(userId -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId)));
		정리할_계정.clear();
	}

	private long 계정을_만든다() {
		Long userId = transactionTemplate.execute(status -> {
			User user = User.createLocalUser("concurrent-" + UUID.randomUUID() + "@fillmap.dev",
				"{noop}Initial1234", "김담당");
			ReflectionTestUtils.setField(user, "role", UserRole.ORG);
			return userRepository.saveAndFlush(user).getId();
		});
		정리할_계정.add(userId);
		return userId;
	}

	private OrgEmailChangeRequest 접수한다(long userId, String email) {
		transactionTemplate.executeWithoutResult(status -> requestRepository.upsertPending(userId, email,
			LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)));
		return requestRepository.findAllByUserId(userId).getFirst();
	}

	// 검증: FR-USER-16
	@Test
	@DisplayName("같은 이메일을 노리는 두 요청의 동시 승인은 늦은 쪽이 1409 다 — 유니크 위반 500 이 아니다")
	void 같은_이메일을_노리는_두_요청의_동시_승인은_늦은_쪽이_1409다() throws Exception {
		String 노리는_이메일 = "contested-" + UUID.randomUUID() + "@fillmap.dev";
		OrgEmailChangeRequest first = 접수한다(계정을_만든다(), 노리는_이메일);
		OrgEmailChangeRequest second = 접수한다(계정을_만든다(), 노리는_이메일);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			Future<EmailChangeApproveResponseDto> left = executor.submit(() -> 승인한다(barrier, first));
			Future<EmailChangeApproveResponseDto> right = executor.submit(() -> 승인한다(barrier, second));

			List<Throwable> failures = Stream.of(결과를_받는다(left), 결과를_받는다(right))
				.filter(Objects::nonNull)
				.toList();

			// 선검사는 경합에 진다 — 둘 다 통과한 뒤 늦은 쪽이 uq_users_email 위반을 만나고, 벌크 UPDATE 라
			// 그 위반이 실행 즉시 떠서 1409 로 번역된다(커밋 시점까지 미뤄지면 잡을 자리가 없다).
			// 늦은 쪽의 실패 출처(선검사 vs 유니크 위반 번역)는 스케줄에 달려 비결정이다 — 번역 경로를
			// 결정적으로 태우려면 프로덕션에 테스트 후크가 필요해 하지 않는다. 계약만 단언한다:
			// 1409 하나 · 그 이메일 보유 계정 1건 · 500 아님.
			assertThat(failures).hasSize(1).first().isInstanceOf(ApiException.class);
			assertThat(((ApiException) failures.getFirst()).getErrorCode())
				.isEqualTo(UserErrorCode.EMAIL_ALREADY_EXISTS);
			assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM users WHERE email = ?", Long.class, 노리는_이메일)).isEqualTo(1L);
		} finally {
			executor.shutdownNow();
		}
	}

	/**
	 * 승인이 전 컬럼 UPDATE 였다면, 승인 트랜잭션이 들고 있던 스냅숏이 그 사이 커밋된 비밀번호를 되돌린다.
	 * 단일 컬럼 UPDATE 면 이메일만 바뀌고 나머지는 <b>다른 트랜잭션이 마지막으로 커밋한 값</b> 그대로다.
	 * <p>
	 * 이 테스트가 pin 하는 것은 "승인이 다른 컬럼을 되돌리지 않는다"까지다. 스테일 스냅숏 경합은 재현
	 * 대상이 아니고(승인이 엔티티를 아예 로드하지 않아 밖에서 그 상태를 만들 수 없다), 단일 컬럼 계약
	 * 자체는 {@code UserRepository.updateEmail} javadoc 이 정본이다.
	 */
	// 검증: FR-USER-16
	@Test
	@DisplayName("승인은 email 컬럼만 갱신해 그 사이 커밋된 비밀번호 변경을 되돌리지 않는다")
	void 승인은_email_컬럼만_갱신해_동시_비밀번호_변경을_되돌리지_않는다() {
		long userId = 계정을_만든다();
		String 새_이메일 = "column-" + UUID.randomUUID() + "@fillmap.dev";
		OrgEmailChangeRequest request = 접수한다(userId, 새_이메일);
		String 접수_후_비밀번호 = 비밀번호(userId);

		// 접수와 승인 사이에 본인이 비밀번호를 바꿔 커밋한다.
		transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
			"UPDATE users SET password_hash = '{noop}ChangedByOwner' WHERE id = ?", userId));

		adminEmailChangeRequestService.approve(request.getId(),
			new EmailChangeApproveRequestDto(request.getCreatedAt()));

		assertThat(jdbcTemplate.queryForObject(
			"SELECT email FROM users WHERE id = ?", String.class, userId)).isEqualTo(새_이메일);
		assertThat(비밀번호(userId)).isEqualTo("{noop}ChangedByOwner").isNotEqualTo(접수_후_비밀번호);
		// 담당자 이름·기관명 같은 나머지 컬럼도 그대로다 — 승인이 건드리는 컬럼은 email 하나다.
		assertThat(jdbcTemplate.queryForObject(
			"SELECT nickname FROM users WHERE id = ?", String.class, userId)).isEqualTo("김담당");
	}

	private String 비밀번호(long userId) {
		return jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
	}

	private EmailChangeApproveResponseDto 승인한다(CyclicBarrier barrier, OrgEmailChangeRequest request)
		throws Exception {
		barrier.await();
		return adminEmailChangeRequestService.approve(request.getId(),
			new EmailChangeApproveRequestDto(request.getCreatedAt()));
	}

	/** 성공이면 null, 실패면 그 예외 — 어느 쪽이 이겼는지는 스케줄에 달려 있어 결과로 가른다. */
	private Throwable 결과를_받는다(Future<EmailChangeApproveResponseDto> future) throws InterruptedException {
		try {
			future.get();
			return null;
		} catch (ExecutionException e) {
			return e.getCause();
		}
	}
}
