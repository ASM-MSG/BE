package com.msg.fillmap.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.oidc.OidcIdTokenVerifier;
import com.msg.fillmap.auth.oidc.OidcUserInfo;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("OidcLoginService")
class OidcLoginServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private TokenProvider tokenProvider;

	@Mock
	private OidcIdTokenVerifier kakaoVerifier;

	@Mock
	private RefreshTokenService refreshTokenService;

	private OidcLoginService oidcLoginService;

	@BeforeEach
	void setUp() {
		given(kakaoVerifier.supports()).willReturn(AuthProvider.KAKAO);
		oidcLoginService = new OidcLoginService(userRepository, tokenProvider, refreshTokenService, List.of(kakaoVerifier));
	}

	@Nested
	@DisplayName("login")
	class Login {

		private final OidcUserInfo info = new OidcUserInfo("kakao-oid-1", "test@kakao.com", "카카오유저");

		@Test
		@DisplayName("성공: 기존에 연동된 유저면 재가입 없이 액세스와 리프레시를 발급한다 (MSG-135)")
		void 소셜_로그인도_리프레시를_발급한다() {
			given(kakaoVerifier.verify("id-token")).willReturn(info);
			User existing = User.createOAuthUser(AuthProvider.KAKAO, info.oid(), info.email(), info.nickname());
			ReflectionTestUtils.setField(existing, "id", 10L);
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, info.oid())).willReturn(Optional.of(existing));
			given(tokenProvider.issueAccessToken(10L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(10L, "device-1")).willReturn("refresh-token");

			LoginResponseDto response = oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1");

			assertThat(response.accessToken()).isEqualTo("jwt-token");
			assertThat(response.refreshToken()).isEqualTo("refresh-token");
			verify(userRepository, never()).insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("성공: 처음 로그인하는 유저면 자동 가입 후 토큰을 발급한다")
		void login_newUser() {
			given(kakaoVerifier.verify("id-token")).willReturn(info);
			User winner = User.createOAuthUser(AuthProvider.KAKAO, info.oid(), info.email(), info.nickname());
			ReflectionTestUtils.setField(winner, "id", 20L);
			// 첫 조회는 부재, 삽입 후 재조회는 방금 들어간 행 — 가입 경로가 두 번 조회한다
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, info.oid()))
				.willReturn(Optional.empty(), Optional.of(winner));
			given(userRepository.existsByEmail(info.email())).willReturn(false);
			given(userRepository.insertOAuthUserIgnoreConflict(
				eq("KAKAO"), eq(info.oid()), eq(info.email()), eq(info.nickname()), anyString())).willReturn(1);
			given(tokenProvider.issueAccessToken(20L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(20L, "device-1")).willReturn("refresh-token");

			LoginResponseDto response = oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1");

			verify(userRepository).insertOAuthUserIgnoreConflict(
				eq("KAKAO"), eq(info.oid()), eq(info.email()), eq(info.nickname()), anyString());
			assertThat(response.accessToken()).isEqualTo("jwt-token");
			assertThat(response.refreshToken()).isEqualTo("refresh-token");
		}

		@Test
		@DisplayName("성공: email 클레임이 없으면(null) 중복 검사 없이 email null 로 가입된다 (MSG-310)")
		void login_newUser_withoutEmail() {
			OidcUserInfo emailless = new OidcUserInfo("kakao-oid-2", null, "카카오유저");
			given(kakaoVerifier.verify("id-token")).willReturn(emailless);
			User winner = User.createOAuthUser(AuthProvider.KAKAO, emailless.oid(), null, emailless.nickname());
			ReflectionTestUtils.setField(winner, "id", 30L);
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, emailless.oid()))
				.willReturn(Optional.empty(), Optional.of(winner));
			given(userRepository.insertOAuthUserIgnoreConflict(
				eq("KAKAO"), eq(emailless.oid()), eq(null), eq(emailless.nickname()), anyString())).willReturn(1);
			given(tokenProvider.issueAccessToken(30L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(30L, "device-1")).willReturn("refresh-token");

			LoginResponseDto response = oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1");

			verify(userRepository).insertOAuthUserIgnoreConflict(
				eq("KAKAO"), eq(emailless.oid()), eq(null), eq(emailless.nickname()), anyString());
			verify(userRepository, never()).existsByEmail(any());
			assertThat(response.accessToken()).isEqualTo("jwt-token");
		}

		@Test
		@DisplayName("성공: 동시 첫 로그인 경합에서 삽입이 무효돼도(0행) 승자 행으로 토큰을 발급한다 (Codex)")
		void login_concurrentFirstLogin_recoversWinner() {
			given(kakaoVerifier.verify("id-token")).willReturn(info);
			User winner = User.createOAuthUser(AuthProvider.KAKAO, info.oid(), info.email(), info.nickname());
			ReflectionTestUtils.setField(winner, "id", 40L);
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, info.oid()))
				.willReturn(Optional.empty(), Optional.of(winner));
			given(userRepository.existsByEmail(info.email())).willReturn(false);
			given(userRepository.insertOAuthUserIgnoreConflict(
				eq("KAKAO"), eq(info.oid()), eq(info.email()), eq(info.nickname()), anyString())).willReturn(0);
			given(tokenProvider.issueAccessToken(40L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(40L, "device-1")).willReturn("refresh-token");

			LoginResponseDto response = oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1");

			assertThat(response.accessToken()).isEqualTo("jwt-token");
		}

		@Test
		@DisplayName("실패: 삽입이 email 충돌로 무효되고 oid 재조회도 비면 EMAIL_ALREADY_EXISTS 다 (Codex)")
		void login_concurrentEmailConflict() {
			given(kakaoVerifier.verify("id-token")).willReturn(info);
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, info.oid()))
				.willReturn(Optional.empty(), Optional.empty());
			given(userRepository.existsByEmail(info.email())).willReturn(false);
			given(userRepository.insertOAuthUserIgnoreConflict(
				eq("KAKAO"), eq(info.oid()), eq(info.email()), eq(info.nickname()), anyString())).willReturn(0);

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1"))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException api = (ApiException)thrown;
					assertThat(api.getErrorCode()).isEqualTo(UserErrorCode.EMAIL_ALREADY_EXISTS);
				});

			verify(refreshTokenService, never()).issue(any(), any());
		}

		@Test
		@DisplayName("실패: 이미 다른 방식으로 가입된 이메일이면 EMAIL_ALREADY_EXISTS ApiException 을 던지고 삽입을 호출하지 않는다")
		void login_emailConflict() {
			given(kakaoVerifier.verify("id-token")).willReturn(info);
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, info.oid())).willReturn(Optional.empty());
			given(userRepository.existsByEmail(info.email())).willReturn(true);

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1"))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException api = (ApiException)thrown;
					assertThat(api.getErrorCode()).isEqualTo(UserErrorCode.EMAIL_ALREADY_EXISTS);
				});

			verify(userRepository, never()).insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any());
			verify(refreshTokenService, never()).issue(any(), any());
		}

		@Test
		@DisplayName("실패: 지원하지 않는 provider 면 UNSUPPORTED_PROVIDER ApiException 을 던지고 verify 를 호출하지 않는다")
		void login_unsupportedProvider() {
			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.LOCAL, "id-token", "device-1"))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					ApiException api = (ApiException)thrown;
					assertThat(api.getErrorCode()).isEqualTo(AuthErrorCode.UNSUPPORTED_PROVIDER);
				});

			verify(kakaoVerifier, never()).verify(any());
			verify(refreshTokenService, never()).issue(any(), any());
		}
	}
}
