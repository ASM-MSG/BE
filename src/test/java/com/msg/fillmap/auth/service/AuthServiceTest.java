package com.msg.fillmap.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import com.msg.fillmap.auth.dto.LoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.SignupRequestDto;
import com.msg.fillmap.auth.dto.SignupResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.notification.service.PushTokenService;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private TokenProvider tokenProvider;

	@Mock
	private RefreshTokenService refreshTokenService;

	@Mock
	private PushTokenService pushTokenService;

	@InjectMocks
	private AuthService authService;

	@Nested
	@DisplayName("signup")
	class Signup {

		private final SignupRequestDto request = new SignupRequestDto(
			"test@example.com", "password123", "테스터"
		);

		// 검증: FR-AUTH-11
		@Test
		@DisplayName("성공: 새 이메일이면 인코딩된 비밀번호로 저장하고 응답 DTO 를 반환한다")
		void signup_success() {
			given(userRepository.existsByEmail(request.email())).willReturn(false);
			given(passwordEncoder.encode(request.password())).willReturn("encoded-hash");
			given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> {
				User saved = invocation.getArgument(0);
				ReflectionTestUtils.setField(saved, "id", 1L);
				ReflectionTestUtils.setField(saved, "createdAt", LocalDateTime.now());
				return saved;
			});

			SignupResponseDto response = authService.signup(request);

			ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
			verify(userRepository).saveAndFlush(captor.capture());
			User persisted = captor.getValue();

			assertThat(persisted.getEmail()).isEqualTo(request.email());
			assertThat(persisted.getNickname()).isEqualTo(request.nickname());
			assertThat(persisted.getPasswordHash()).isEqualTo("encoded-hash");
			assertThat(persisted.getProvider()).isEqualTo(AuthProvider.LOCAL);
			assertThat(persisted.getRole()).isEqualTo(UserRole.USER);
			assertThat(persisted.isEmailVerified()).isFalse();

			assertThat(response.id()).isEqualTo(1L);
			assertThat(response.email()).isEqualTo(request.email());
			assertThat(response.nickname()).isEqualTo(request.nickname());
			assertThat(response.createdAt()).isNotNull();
		}

		// 검증: FR-USER-04
		@Test
		@DisplayName("실패: 이메일이 이미 존재하면 EMAIL_ALREADY_EXISTS ApiException 을 던지고 삽입을 호출하지 않는다")
		void signup_duplicateEmail() {
			given(userRepository.existsByEmail(request.email())).willReturn(true);

			assertThatThrownBy(() -> authService.signup(request))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException apiException = (ApiException) thrown;
					assertThat(apiException.getErrorCode()).isEqualTo(UserErrorCode.EMAIL_ALREADY_EXISTS);
				});

			verify(passwordEncoder, never()).encode(any());
			verify(userRepository, never()).saveAndFlush(any());
		}

		// 검증: FR-USER-04
		@Test
		@DisplayName("실패: 선확인을 통과해도 삽입에서 이메일 UNIQUE 충돌이 나면 EMAIL_ALREADY_EXISTS 다 (동시 가입 경합)")
		void signup_concurrentDuplicateEmail() {
			given(userRepository.existsByEmail(request.email())).willReturn(false);
			given(passwordEncoder.encode(request.password())).willReturn("encoded-hash");
			given(userRepository.saveAndFlush(any(User.class)))
				.willThrow(new DataIntegrityViolationException("uq_users_email"));

			assertThatThrownBy(() -> authService.signup(request))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException apiException = (ApiException) thrown;
					assertThat(apiException.getErrorCode()).isEqualTo(UserErrorCode.EMAIL_ALREADY_EXISTS);
				});
		}
	}

	@Nested
	@DisplayName("login")
	class Login {

		private final LoginRequestDto request = new LoginRequestDto("test@example.com", "password123");

		// 검증: FR-AUTH-05
		@Test
		@DisplayName("성공: 이메일·비밀번호가 맞으면 액세스와 리프레시를 함께 발급·저장해 반환한다 (MSG-135)")
		void 로그인하면_액세스와_리프레시가_함께_발급되고_저장된다() {
			User user = User.createLocalUser(request.email(), "encoded-hash", "테스터");
			ReflectionTestUtils.setField(user, "id", 42L);

			given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));
			given(passwordEncoder.matches(request.password(), "encoded-hash")).willReturn(true);
			given(tokenProvider.issueAccessToken(42L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(42L, "device-1")).willReturn("refresh-token");

			LoginResponseDto response = authService.login(request, "device-1");

			assertThat(response.accessToken()).isEqualTo("jwt-token");
			assertThat(response.refreshToken()).isEqualTo("refresh-token");
			verify(refreshTokenService).issue(42L, "device-1");
		}

		@Test
		@DisplayName("실패: 이메일이 존재하지 않으면 INVALID_CREDENTIALS 를 던지고 후속 호출 없음")
		void login_emailNotFound() {
			given(userRepository.findByEmail(request.email())).willReturn(Optional.empty());

			assertThatThrownBy(() -> authService.login(request, "device-1"))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(((ApiException) thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));

			verify(passwordEncoder, never()).matches(any(), any());
			verify(tokenProvider, never()).issueAccessToken(any(), any());
			verify(refreshTokenService, never()).issue(any(), any());
		}

		@Test
		@DisplayName("실패: 비밀번호가 불일치하면 INVALID_CREDENTIALS 를 던지고 토큰을 발급하지 않는다")
		void login_wrongPassword() {
			User user = User.createLocalUser(request.email(), "encoded-hash", "테스터");
			ReflectionTestUtils.setField(user, "id", 42L);

			given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));
			given(passwordEncoder.matches(request.password(), "encoded-hash")).willReturn(false);

			assertThatThrownBy(() -> authService.login(request, "device-1"))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(((ApiException) thrown).getErrorCode())
					.isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));

			verify(tokenProvider, never()).issueAccessToken(any(), any());
			verify(refreshTokenService, never()).issue(any(), any());
		}
	}

	@Nested
	@DisplayName("logout")
	class Logout {

		// 검증: FR-AUTH-06, FR-AUTH-09
		@Test
		@DisplayName("성공: 액세스를 블랙리스트에 올리고 해당 디바이스 리프레시를 삭제한다 (MSG-135)")
		void 로그아웃하면_액세스가_블랙리스트에_오르고_해당_디바이스_리프레시가_삭제된다() {
			given(tokenProvider.parseAccessToken("jwt-token")).willReturn(new AuthPrincipal(42L, UserRole.USER));

			authService.logout("jwt-token", "device-1", null);

			verify(tokenProvider).invalidateAccessToken("jwt-token");
			verify(refreshTokenService).delete(42L, "device-1");
			verify(refreshTokenService, never()).deleteAll(any());
			// fcmToken 이 없으면 푸시 토큰 정리는 호출되지 않는다 (MSG-178 하위 호환)
			verify(pushTokenService, never()).unregister(anyLong(), any());
		}

		@Test
		@DisplayName("성공: X-Device-Id 가 없으면 모든 디바이스 리프레시를 삭제한다 — 로그아웃-올 폴백 (MSG-135 확정 결정 5)")
		void 디바이스_id가_없으면_모든_디바이스_리프레시를_삭제한다() {
			given(tokenProvider.parseAccessToken("jwt-token")).willReturn(new AuthPrincipal(42L, UserRole.USER));

			authService.logout("jwt-token", null, null);

			verify(tokenProvider).invalidateAccessToken("jwt-token");
			verify(refreshTokenService).deleteAll(42L);
			verify(refreshTokenService, never()).delete(any(), any());
		}

		// 검증: FR-NOTI-01
		@Test
		@DisplayName("성공: fcmToken 이 있으면 파싱한 userId 로 푸시 토큰도 함께 정리한다 (MSG-178 logout 통합)")
		void fcmToken이_있으면_푸시_토큰도_함께_정리한다() {
			given(tokenProvider.parseAccessToken("jwt-token")).willReturn(new AuthPrincipal(42L, UserRole.USER));

			authService.logout("jwt-token", "device-1", "fcm-token-abc");

			verify(pushTokenService).unregister(42L, "fcm-token-abc");
			verify(refreshTokenService).delete(42L, "device-1");
		}
	}
}
