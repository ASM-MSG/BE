package com.msg.fillmap.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.auth.dto.SignupRequestDto;
import com.msg.fillmap.auth.dto.SignupResponseDto;
import com.msg.fillmap.global.exception.ApiException;
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

	@InjectMocks
	private AuthService authService;

	@Nested
	@DisplayName("signup")
	class Signup {

		private final SignupRequestDto request = new SignupRequestDto(
			"test@example.com", "password123", "테스터"
		);

		@Test
		@DisplayName("성공: 새 이메일이면 인코딩된 비밀번호로 저장하고 응답 DTO 를 반환한다")
		void signup_success() {
			given(userRepository.existsByEmail(request.email())).willReturn(false);
			given(passwordEncoder.encode(request.password())).willReturn("encoded-hash");
			given(userRepository.save(any(User.class))).willAnswer(invocation -> {
				User saved = invocation.getArgument(0);
				ReflectionTestUtils.setField(saved, "id", 1L);
				ReflectionTestUtils.setField(saved, "createdAt", LocalDateTime.now());
				return saved;
			});

			SignupResponseDto response = authService.signup(request);

			ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
			verify(userRepository).save(captor.capture());
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

		@Test
		@DisplayName("실패: 이메일이 이미 존재하면 EMAIL_ALREADY_EXISTS ApiException 을 던지고 save 를 호출하지 않는다")
		void signup_duplicateEmail() {
			given(userRepository.existsByEmail(request.email())).willReturn(true);

			assertThatThrownBy(() -> authService.signup(request))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException apiException = (ApiException) thrown;
					assertThat(apiException.getErrorCode()).isEqualTo(UserErrorCode.EMAIL_ALREADY_EXISTS);
				});

			verify(passwordEncoder, never()).encode(any());
			verify(userRepository, never()).save(any());
		}
	}
}
