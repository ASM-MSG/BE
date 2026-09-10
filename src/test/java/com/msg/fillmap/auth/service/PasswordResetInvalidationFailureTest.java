package com.msg.fillmap.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.auth.dto.PasswordResetConfirmRequestDto;
import com.msg.fillmap.auth.dto.PasswordResetRequestDto;
import com.msg.fillmap.auth.jwt.InvalidatedTokenStore;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 재설정 중 토큰 무효화 기록이 실패했을 때의 수렴 (MSG-497, 실 DB·실 Redis). 무효화 기록은 비밀번호
 * 저장과 같은 트랜잭션 안이라, Redis 장애면 저장까지 함께 롤백돼 "비밀번호는 바뀌었는데 옛 토큰이 전부
 * 살아 있는" 상태가 생기지 않는다.
 *
 * <p>진짜 커밋·롤백을 봐야 해서 클래스 수준 {@code @Transactional} 롤백 격리를 쓸 수 없다(그 안에서는
 * 롤백이 테스트 종료까지 미뤄져 저장 여부를 가릴 수 없다). 대신 사용자 행을 직접 지운다.
 */
@SpringBootTest
@DisplayName("재설정 중 무효화 기록 실패 (실 DB)")
class PasswordResetInvalidationFailureTest {

	private static final String INITIAL_PASSWORD = "Initial1234";
	private static final String NEW_PASSWORD = "Fillmap5678";

	@Autowired
	private PasswordService passwordService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private MailSender mailSender;

	/** Redis 장애 주입 지점 — 무효화 기록만 예외로 바꾼다. */
	@MockitoSpyBean
	private InvalidatedTokenStore invalidatedTokenStore;

	private Long userId;
	private String email;

	@BeforeEach
	void setUp() {
		email = "organizer-" + UUID.randomUUID() + "@fillmap.dev";
		userId = transactionTemplate.execute(status -> {
			User user = User.createLocalUser(email, passwordEncoder.encode(INITIAL_PASSWORD), "담당자");
			ReflectionTestUtils.setField(user, "role", UserRole.ORG);
			return userRepository.save(user).getId();
		});
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> userRepository.deleteUser(userId));
	}

	private String 링크의_토큰을_받는다() {
		passwordService.requestReset(new PasswordResetRequestDto(email));
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		then(mailSender).should().send(eq(email), anyString(), body.capture());
		String mail = body.getValue();
		return mail.substring(mail.indexOf("?token=") + "?token=".length()).split("\\s")[0];
	}

	private String 저장된_해시() {
		return transactionTemplate.execute(
			status -> userRepository.findById(userId).orElseThrow().getPasswordHash());
	}

	// 검증: FR-AUTH-16
	@Test
	@DisplayName("무효화 기록이 실패하면 비밀번호가 그대로이고 링크는 되살아난다 — fail-closed")
	void 무효화_기록이_실패하면_비밀번호가_바뀌지_않고_토큰이_복원된다() {
		String token = 링크의_토큰을_받는다();
		String before = 저장된_해시();
		willThrow(new IllegalStateException("Redis 장애")).given(invalidatedTokenStore)
			.invalidateUser(anyLong(), any(), any());

		assertThatThrownBy(() -> passwordService.resetPassword(
			new PasswordResetConfirmRequestDto(token, NEW_PASSWORD)))
			.isInstanceOf(IllegalStateException.class);

		assertThat(저장된_해시()).isEqualTo(before);
		assertThat(passwordEncoder.matches(INITIAL_PASSWORD, 저장된_해시())).isTrue();

		// 복원된 링크로 다시 시도하면 이번엔 끝까지 간다 — 사용자는 재요청 없이 재시도만 하면 된다.
		willCallRealMethod().given(invalidatedTokenStore).invalidateUser(anyLong(), any(), any());
		passwordService.resetPassword(new PasswordResetConfirmRequestDto(token, NEW_PASSWORD));

		assertThat(passwordEncoder.matches(NEW_PASSWORD, 저장된_해시())).isTrue();
	}

	// 검증: FR-AUTH-16
	@Test
	@DisplayName("커밋 후 무효화 시각이 한 번 더 갱신된다 — 첫 기록과 커밋 사이 로그인 창을 닫는다")
	void 커밋_후_무효화_시각이_트랜잭션_내_시각_이상으로_갱신된다() {
		String token = 링크의_토큰을_받는다();

		passwordService.resetPassword(new PasswordResetConfirmRequestDto(token, NEW_PASSWORD));

		ArgumentCaptor<Instant> invalidatedAt = ArgumentCaptor.forClass(Instant.class);
		then(invalidatedTokenStore).should(times(2)).invalidateUser(eq(userId), invalidatedAt.capture(), any());
		assertThat(invalidatedAt.getAllValues().get(1)).isAfterOrEqualTo(invalidatedAt.getAllValues().get(0));
	}
}
