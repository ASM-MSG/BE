package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;

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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.auth.dto.PasswordChangeRequestDto;
import com.msg.fillmap.auth.dto.PasswordResetConfirmRequestDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.password.PasswordResetTokenStore;
import com.msg.fillmap.auth.service.PasswordService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 초기 비밀번호 재발송과 비밀번호 변경·재설정의 경합 (MSG-499 API 7, 실 DB). 재발송이 이전 초기
 * 비밀번호를 즉시 무효화한다는 것은 유출 시나리오의 보안 계약이라, 세 경로가 같은 행 잠금을 거치지
 * 않아 낡은 스냅숏이 최신 커밋을 덮으면(lost update) 수용이 아니라 결함이다.
 *
 * <p>진짜 동시 트랜잭션이 필요해 클래스 수준 {@code @Transactional} 롤백 격리를 쓸 수 없다
 * (OrgEmailChangeConcurrencyTest 와 같은 이유). 대신 만들어진 계정을 직접 지워 공유 로컬 DB 를 되돌린다.
 */
@SpringBootTest
@DisplayName("초기 비밀번호 재발송 경합 (실 DB)")
class InitialPasswordResendConcurrencyTest {

	private static final String INITIAL_PASSWORD = "Initial1234";
	private static final String CHANGED_PASSWORD = "Fillmap5678";
	private static final String RESET_PASSWORD = "Reset999999";

	@Autowired
	private OrgAccountIssueService orgAccountIssueService;

	@Autowired
	private PasswordService passwordService;

	@Autowired
	private PasswordResetTokenStore passwordResetTokenStore;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private MailSender mailSender;

	private Long userId;

	@BeforeEach
	void setUp() {
		userId = transactionTemplate.execute(status -> userRepository.save(
			User.createOrgUser("resend-" + UUID.randomUUID() + "@fillmap.dev",
				passwordEncoder.encode(INITIAL_PASSWORD), "김담당", "010-1234-5678", "부산진구청")).getId());
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> userRepository.deleteUser(userId));
	}

	private User 다시_읽는다() {
		return transactionTemplate.execute(status -> userRepository.findById(userId).orElseThrow());
	}

	private String 마지막으로_발송된_비밀번호() {
		ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
		then(mailSender).should().send(any(), any(), bodyCaptor.capture());
		String body = bodyCaptor.getValue();
		int begin = body.indexOf("초기 비밀번호: ") + "초기 비밀번호: ".length();
		return body.substring(begin, body.indexOf('\n', begin));
	}

	private void 본인이_변경한다(String currentPassword) {
		passwordService.changePassword(userId, new PasswordChangeRequestDto(currentPassword, CHANGED_PASSWORD));
	}

	private void 재설정한다() {
		String token = "reset-" + UUID.randomUUID();
		passwordResetTokenStore.save(token, userId);
		passwordService.resetPassword(new PasswordResetConfirmRequestDto(token, RESET_PASSWORD));
	}

	// 검증: FR-AUTH-13
	@Test
	@DisplayName("본인 비밀번호 변경과 경합하는 재발송은 늦게 커밋해도 1423 이고 mustChange 가 false 로 유지된다")
	void 본인_비밀번호_변경과_경합하는_재발송은_늦게_커밋해도_1423이다() {
		본인이_변경한다(INITIAL_PASSWORD);

		assertThatThrownBy(() -> orgAccountIssueService.resendInitialPassword(userId))
			.isInstanceOf(ApiException.class)
			.extracting(exception -> ((ApiException) exception).getErrorCode())
			.isEqualTo(UserErrorCode.INITIAL_PASSWORD_RESEND_NOT_ALLOWED);

		User reloaded = 다시_읽는다();
		assertThat(reloaded.isPasswordMustChange()).isFalse();
		// 사용자가 정한 비밀번호가 그대로다 — 재발송이 잠금 안 재검사를 건너뛰었다면 여기서 덮였다.
		assertThat(passwordEncoder.matches(CHANGED_PASSWORD, reloaded.getPasswordHash())).isTrue();
		then(mailSender).shouldHaveNoInteractions();
	}

	// 검증: FR-AUTH-13
	// 검증: FR-AUTH-16
	@Test
	@DisplayName("재발송이 먼저 커밋한 뒤의 본인 변경은 이전 초기 비밀번호로 2442 다")
	void 재발송이_먼저_커밋한_뒤의_본인_변경은_이전_초기_비밀번호로_2442다() {
		orgAccountIssueService.resendInitialPassword(userId);
		String reissued = 마지막으로_발송된_비밀번호();

		assertThatThrownBy(() -> 본인이_변경한다(INITIAL_PASSWORD))
			.isInstanceOf(ApiException.class)
			.extracting(exception -> ((ApiException) exception).getErrorCode())
			.isEqualTo(AuthErrorCode.CURRENT_PASSWORD_MISMATCH);

		// 새 메일의 비밀번호로는 그대로 변경된다 — 사용자가 막히는 것이 아니라 이전 값만 죽는다.
		본인이_변경한다(reissued);
		User reloaded = 다시_읽는다();
		assertThat(reloaded.isPasswordMustChange()).isFalse();
		assertThat(passwordEncoder.matches(CHANGED_PASSWORD, reloaded.getPasswordHash())).isTrue();
	}

	// 검증: FR-AUTH-13
	@Test
	@DisplayName("본인 변경과 재발송이 동시에 일어나도 한쪽 결과만 남는다 — 낡은 스냅숏이 덮지 않는다")
	void 본인_변경과_재발송이_동시에_일어나도_한쪽_결과만_남는다() throws Exception {
		List<Throwable> failures = 동시에_실행한다(() -> 본인이_변경한다(INITIAL_PASSWORD),
			() -> orgAccountIssueService.resendInitialPassword(userId));

		User reloaded = 다시_읽는다();
		if (failures.isEmpty()) {
			// 재발송이 먼저 커밋했고 변경이 새 값으로 성공한 순서는 없다(변경은 옛 비밀번호를 썼다).
			// 두 요청이 모두 성공했다면 변경이 먼저 커밋하고 재발송이 그 뒤에 통과한 것이라 계약 위반이다.
			throw new AssertionError("두 경로가 모두 성공했다 — 잠금 안 재검사가 동작하지 않았다");
		}
		assertThat(failures).hasSize(1);
		if (reloaded.isPasswordMustChange()) {
			// 재발송이 이겼다 — 변경은 현재 비밀번호 불일치로 걸리고 새 초기 비밀번호만 유효하다.
			assertThat(((ApiException) failures.getFirst()).getErrorCode())
				.isEqualTo(AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
			assertThat(passwordEncoder.matches(마지막으로_발송된_비밀번호(), reloaded.getPasswordHash())).isTrue();
		} else {
			// 본인 변경이 이겼다 — 재발송은 잠금 안 재검사에서 1423 으로 끝나고 발송이 없다.
			assertThat(((ApiException) failures.getFirst()).getErrorCode())
				.isEqualTo(UserErrorCode.INITIAL_PASSWORD_RESEND_NOT_ALLOWED);
			assertThat(passwordEncoder.matches(CHANGED_PASSWORD, reloaded.getPasswordHash())).isTrue();
			then(mailSender).shouldHaveNoInteractions();
		}
	}

	// 검증: FR-AUTH-13
	@Test
	@DisplayName("재설정과 재발송이 경합하면 늦게 읽은 쪽이 락 대기 후 최신 상태로 판정된다")
	void 재설정과_재발송이_경합하면_늦게_읽은_쪽이_락_대기_후_최신_상태로_판정된다() throws Exception {
		List<Throwable> failures = 동시에_실행한다(this::재설정한다,
			() -> orgAccountIssueService.resendInitialPassword(userId));

		User reloaded = 다시_읽는다();
		if (reloaded.isPasswordMustChange()) {
			// 재발송이 나중에 커밋했다 — 재설정 결과 위에 새 초기 비밀번호가 얹혔고 플래그가 다시 섰다.
			assertThat(failures).isEmpty();
			assertThat(passwordEncoder.matches(마지막으로_발송된_비밀번호(), reloaded.getPasswordHash())).isTrue();
			assertThat(passwordEncoder.matches(RESET_PASSWORD, reloaded.getPasswordHash())).isFalse();
		} else {
			// 재설정이 나중에 커밋했다 — 그 비밀번호만 유효하고 재발송이 세운 플래그도 함께 내려갔다.
			// 해시와 플래그가 서로 다른 트랜잭션에서 온 짝(재설정 해시 + true 같은 것)은 나올 수 없다.
			assertThat(passwordEncoder.matches(RESET_PASSWORD, reloaded.getPasswordHash())).isTrue();
		}
	}

	/** 두 작업을 같은 순간에 시작시키고 각각의 실패(성공이면 null 제외)를 모은다. */
	private List<Throwable> 동시에_실행한다(Runnable first, Runnable second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			Future<?> left = executor.submit(() -> 신호를_기다렸다_실행한다(barrier, first));
			Future<?> right = executor.submit(() -> 신호를_기다렸다_실행한다(barrier, second));
			return Stream.of(결과를_받는다(left), 결과를_받는다(right))
				.filter(Objects::nonNull)
				.toList();
		} finally {
			executor.shutdownNow();
		}
	}

	private Object 신호를_기다렸다_실행한다(CyclicBarrier barrier, Runnable task) throws Exception {
		barrier.await();
		task.run();
		return null;
	}

	private Throwable 결과를_받는다(Future<?> future) throws InterruptedException {
		try {
			future.get();
			return null;
		} catch (ExecutionException e) {
			return e.getCause();
		}
	}
}
