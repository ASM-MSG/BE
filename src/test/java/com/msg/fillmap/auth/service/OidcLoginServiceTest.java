package com.msg.fillmap.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.OidcLoginRequestDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.oidc.AppleOidcIdTokenVerifier;
import com.msg.fillmap.auth.oidc.AppleRefreshTokenCipher;
import com.msg.fillmap.auth.oidc.AppleTokenClient;
import com.msg.fillmap.auth.oidc.OidcIdTokenVerifier;
import com.msg.fillmap.auth.oidc.OidcUserInfo;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.ErrorCodeIfs;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("OidcLoginService")
class OidcLoginServiceTest {

	private static final String DEFAULT_NICKNAME_PATTERN = "필맵러\\d{4}";

	@Mock
	private UserRepository userRepository;

	@Mock
	private TokenProvider tokenProvider;

	@Mock
	private OidcIdTokenVerifier kakaoVerifier;

	@Mock
	private AppleOidcIdTokenVerifier appleVerifier;

	@Mock
	private RefreshTokenService refreshTokenService;

	@Mock
	private AppleTokenClient appleTokenClient;

	@Mock
	private AppleRefreshTokenCipher appleRefreshTokenCipher;

	@Mock
	private TransactionTemplate transactionTemplate;

	private OidcLoginService oidcLoginService;

	@BeforeEach
	void setUp() {
		given(kakaoVerifier.supports()).willReturn(AuthProvider.KAKAO);
		given(appleVerifier.supports()).willReturn(AuthProvider.APPLE);
		// 쓰기 경계 스텁 — 콜백을 그대로 실행한다(트랜잭션 없음). 경계 밖 실패 테스트에서는 안 불리므로 lenient.
		lenient().when(transactionTemplate.execute(any()))
			.thenAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		oidcLoginService = new OidcLoginService(userRepository, tokenProvider, refreshTokenService,
			List.of(kakaoVerifier, appleVerifier), appleTokenClient, appleRefreshTokenCipher, appleVerifier,
			transactionTemplate);
	}

	private static void assertErrorCode(Throwable thrown, ErrorCodeIfs expected) {
		assertThat(thrown).isInstanceOf(ApiException.class);
		assertThat(((ApiException)thrown).getErrorCode()).isEqualTo(expected);
	}

	private static String storedAppleToken(User user) {
		return (String)ReflectionTestUtils.getField(user, "appleRefreshTokenEncrypted");
	}

	@Nested
	@DisplayName("login")
	class Login {

		private final OidcUserInfo info = new OidcUserInfo("kakao-oid-1", "test@kakao.com", "카카오유저");

		// 검증: FR-AUTH-01, FR-AUTH-05
		@Test
		@DisplayName("성공: 기존에 연동된 유저면 재가입 없이 액세스와 리프레시를 발급한다 (MSG-135)")
		void 소셜_로그인도_리프레시를_발급한다() {
			given(kakaoVerifier.verify("id-token", null)).willReturn(info);
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

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("성공: 카카오 로그인 응답에는 USER 역할이 실린다 — 소셜 가입자는 항상 일반 사용자 (MSG-496)")
		void 카카오_로그인_응답에는_USER_역할이_실린다() {
			given(kakaoVerifier.verify("id-token", null)).willReturn(info);
			User existing = User.createOAuthUser(AuthProvider.KAKAO, info.oid(), info.email(), info.nickname());
			ReflectionTestUtils.setField(existing, "id", 11L);
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, info.oid())).willReturn(Optional.of(existing));
			given(tokenProvider.issueAccessToken(11L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(11L, "device-1")).willReturn("refresh-token");

			LoginResponseDto response = oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1");

			assertThat(response.role()).isEqualTo("USER");
		}

		// 검증: FR-AUTH-01
		@Test
		@DisplayName("성공: 처음 로그인하는 유저면 자동 가입 후 토큰을 발급한다")
		void login_newUser() {
			given(kakaoVerifier.verify("id-token", null)).willReturn(info);
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

		// 검증: FR-USER-04
		@Test
		@DisplayName("성공: email 클레임이 없으면(null) 중복 검사 없이 email null 로 가입된다 (MSG-310)")
		void login_newUser_withoutEmail() {
			OidcUserInfo emailless = new OidcUserInfo("kakao-oid-2", null, "카카오유저");
			given(kakaoVerifier.verify("id-token", null)).willReturn(emailless);
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

		// 검증: FR-AUTH-01
		@Test
		@DisplayName("성공: 동시 첫 로그인 경합에서 삽입이 무효돼도(0행) 승자 행으로 토큰을 발급한다 (Codex)")
		void login_concurrentFirstLogin_recoversWinner() {
			given(kakaoVerifier.verify("id-token", null)).willReturn(info);
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

		// 검증: FR-USER-04
		@Test
		@DisplayName("실패: 삽입이 email 충돌로 무효되고 oid 재조회도 비면 EMAIL_ALREADY_EXISTS 다 (Codex)")
		void login_concurrentEmailConflict() {
			given(kakaoVerifier.verify("id-token", null)).willReturn(info);
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, info.oid()))
				.willReturn(Optional.empty(), Optional.empty());
			given(userRepository.existsByEmail(info.email())).willReturn(false);
			given(userRepository.insertOAuthUserIgnoreConflict(
				eq("KAKAO"), eq(info.oid()), eq(info.email()), eq(info.nickname()), anyString())).willReturn(0);

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1"))
				.satisfies(thrown -> assertErrorCode(thrown, UserErrorCode.EMAIL_ALREADY_EXISTS));

			verify(refreshTokenService, never()).issue(any(), any());
		}

		// 검증: FR-USER-04
		@Test
		@DisplayName("실패: 이미 다른 방식으로 가입된 이메일이면 EMAIL_ALREADY_EXISTS ApiException 을 던지고 삽입을 호출하지 않는다")
		void login_emailConflict() {
			given(kakaoVerifier.verify("id-token", null)).willReturn(info);
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, info.oid())).willReturn(Optional.empty());
			given(userRepository.existsByEmail(info.email())).willReturn(true);

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1"))
				.satisfies(thrown -> assertErrorCode(thrown, UserErrorCode.EMAIL_ALREADY_EXISTS));

			verify(userRepository, never()).insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any());
			verify(refreshTokenService, never()).issue(any(), any());
		}

		// 검증: FR-AUTH-01
		@Test
		@DisplayName("실패: 지원하지 않는 provider 면 UNSUPPORTED_PROVIDER ApiException 을 던지고 verify 를 호출하지 않는다")
		void login_unsupportedProvider() {
			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.LOCAL, "id-token", "device-1"))
				.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.UNSUPPORTED_PROVIDER));

			verify(kakaoVerifier, never()).verify(any(), any());
			verify(refreshTokenService, never()).issue(any(), any());
		}

		// 검증: FR-AUTH-12, D-12
		@Test
		@DisplayName("성공: 카카오 nickname 클레임이 없어도 기본 닉네임으로 가입된다 — NOT NULL 삽입 500 이던 잠재 결함 제거")
		void 카카오_nickname_클레임이_없어도_기본_닉네임으로_가입된다() {
			OidcUserInfo nameless = new OidcUserInfo("kakao-oid-3", null, null);
			given(kakaoVerifier.verify("id-token", null)).willReturn(nameless);
			User winner = User.createOAuthUser(AuthProvider.KAKAO, nameless.oid(), null, "필맵러0000");
			ReflectionTestUtils.setField(winner, "id", 50L);
			given(userRepository.findByProviderAndOid(AuthProvider.KAKAO, nameless.oid()))
				.willReturn(Optional.empty(), Optional.of(winner));
			given(userRepository.insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any())).willReturn(1);
			given(tokenProvider.issueAccessToken(50L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(50L, "device-1")).willReturn("refresh-token");

			oidcLoginService.login(AuthProvider.KAKAO, "id-token", "device-1");

			ArgumentCaptor<String> nickname = ArgumentCaptor.forClass(String.class);
			verify(userRepository).insertOAuthUserIgnoreConflict(
				eq("KAKAO"), eq(nameless.oid()), eq(null), nickname.capture(), anyString());
			assertThat(nickname.getValue()).matches(DEFAULT_NICKNAME_PATTERN);
		}
	}

	@Nested
	@DisplayName("login — APPLE (MSG-594)")
	class AppleLogin {

		private static final String ID_TOKEN = "apple-id-token";
		private static final String NONCE = "raw-nonce";
		private static final String CODE = "apple-auth-code";
		private static final String EXCHANGED_ID_TOKEN = "exchanged-id-token";
		private static final String PLAIN_REFRESH = "apple-refresh-plain";
		private static final String ENCRYPTED = "ENC(apple-refresh)";

		private final OidcUserInfo info = new OidcUserInfo("001234.abcd", "u@privaterelay.appleid.com", null);

		private OidcLoginRequestDto request(String code, String fullName) {
			return new OidcLoginRequestDto(ID_TOKEN, NONCE, code, fullName);
		}

		private User appleUser(long id, String nickname) {
			User user = User.createOAuthUser(AuthProvider.APPLE, info.oid(), info.email(), nickname);
			ReflectionTestUtils.setField(user, "id", id);
			return user;
		}

		/** 첫 로그인 스텁 묶음 — 검증 통과, 경계 밖 존재 조회 부재, 교환·sub 대조·암호화 성공, 쓰기 경계 안 삽입 1행. */
		private User stubFirstLogin(String nickname) {
			given(appleVerifier.verify(ID_TOKEN, NONCE)).willReturn(info);
			User winner = appleUser(100L, nickname);
			// 경계 밖 존재 조회 → 경계 안 find-or-create 조회 → 삽입 후 재조회 순으로 세 번 조회한다
			given(userRepository.findByProviderAndOid(AuthProvider.APPLE, info.oid()))
				.willReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
			given(appleTokenClient.exchange(CODE))
				.willReturn(new AppleTokenClient.Exchanged(PLAIN_REFRESH, EXCHANGED_ID_TOKEN));
			given(appleVerifier.verifySubject(EXCHANGED_ID_TOKEN)).willReturn(info.oid());
			given(appleRefreshTokenCipher.encrypt(PLAIN_REFRESH)).willReturn(ENCRYPTED);
			given(userRepository.existsByEmail(info.email())).willReturn(false);
			given(userRepository.insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any())).willReturn(1);
			given(tokenProvider.issueAccessToken(100L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(100L, "device-1")).willReturn("refresh-token");
			return winner;
		}

		private String insertedNickname() {
			ArgumentCaptor<String> nickname = ArgumentCaptor.forClass(String.class);
			verify(userRepository).insertOAuthUserIgnoreConflict(
				eq("APPLE"), eq(info.oid()), eq(info.email()), nickname.capture(), anyString());
			return nickname.getValue();
		}

		// 검증: FR-AUTH-12, AC-594-08
		@Test
		@DisplayName("첫 로그인은 인가 코드를 교환하고 암호문을 저장한다 — 평문은 엔티티에 닿지 않는다")
		void 애플_첫_로그인은_인가_코드를_교환하고_암호문을_저장한다() {
			User winner = stubFirstLogin("김필맵");

			LoginResponseDto response = oidcLoginService.login(AuthProvider.APPLE, request(CODE, "김필맵"), "device-1");

			assertThat(response.accessToken()).isEqualTo("jwt-token");
			assertThat(storedAppleToken(winner)).isEqualTo(ENCRYPTED).isNotEqualTo(PLAIN_REFRESH);
		}

		// 검증: FR-AUTH-12, AC-594-09, D-8
		@Test
		@DisplayName("교환은 트랜잭션 경계 밖에서 끝난다 — execute 보다 먼저 호출되고 콜백 안에서는 호출이 없다")
		void 교환은_트랜잭션_경계_밖에서_끝난다() {
			stubFirstLogin("김필맵");
			// given(mock.execute(any())) 은 setUp 의 answer 를 null 인자로 실제 호출하므로 호출 없는 스텁 형식을 쓴다
			willAnswer(invocation -> {
				// 콜백 진입 시점에 교환이 이미 끝나 있어야 한다
				verify(appleTokenClient, times(1)).exchange(CODE);
				return invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null);
			}).given(transactionTemplate).execute(any());

			oidcLoginService.login(AuthProvider.APPLE, request(CODE, "김필맵"), "device-1");

			InOrder order = inOrder(appleTokenClient, appleRefreshTokenCipher, transactionTemplate);
			order.verify(appleTokenClient).exchange(CODE);
			order.verify(appleRefreshTokenCipher).encrypt(PLAIN_REFRESH);
			order.verify(transactionTemplate).execute(any());
			verify(appleTokenClient, times(1)).exchange(any());	// 콜백 안 추가 호출 없음
		}

		// 검증: FR-AUTH-12, AC-594-04
		@Test
		void 애플_재로그인은_계정을_만들지_않고_교환도_하지_않는다() {
			given(appleVerifier.verify(ID_TOKEN, NONCE)).willReturn(info);
			User existing = appleUser(101L, "김필맵");
			given(userRepository.findByProviderAndOid(AuthProvider.APPLE, info.oid()))
				.willReturn(Optional.of(existing));
			given(tokenProvider.issueAccessToken(101L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(101L, "device-1")).willReturn("refresh-token");

			LoginResponseDto response = oidcLoginService.login(AuthProvider.APPLE, request(CODE, null), "device-1");

			assertThat(response.accessToken()).isEqualTo("jwt-token");
			verifyNoInteractions(appleTokenClient, appleRefreshTokenCipher);
			verify(userRepository, never()).insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any());
			assertThat(storedAppleToken(existing)).isNull();
		}

		// 검증: FR-AUTH-12, AC-594-15
		@Test
		void 교환_응답_id_token의_sub가_요청_토큰과_다르면_2423이고_아무것도_저장하지_않는다() {
			given(appleVerifier.verify(ID_TOKEN, NONCE)).willReturn(info);
			given(userRepository.findByProviderAndOid(AuthProvider.APPLE, info.oid())).willReturn(Optional.empty());
			given(appleTokenClient.exchange(CODE))
				.willReturn(new AppleTokenClient.Exchanged(PLAIN_REFRESH, EXCHANGED_ID_TOKEN));
			given(appleVerifier.verifySubject(EXCHANGED_ID_TOKEN)).willReturn("009999.other-account");

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.APPLE, request(CODE, null), "device-1"))
				.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_AUTHORIZATION_CODE));

			verify(transactionTemplate, never()).execute(any());
			verify(userRepository, never()).insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any());
			verifyNoInteractions(appleRefreshTokenCipher, refreshTokenService);
		}

		// 검증: FR-AUTH-12, AC-594-15
		@Test
		void 교환_응답_id_token의_검증이_실패하면_2423이다() {
			given(appleVerifier.verify(ID_TOKEN, NONCE)).willReturn(info);
			given(userRepository.findByProviderAndOid(AuthProvider.APPLE, info.oid())).willReturn(Optional.empty());
			given(appleTokenClient.exchange(CODE))
				.willReturn(new AppleTokenClient.Exchanged(PLAIN_REFRESH, EXCHANGED_ID_TOKEN));
			given(appleVerifier.verifySubject(EXCHANGED_ID_TOKEN))
				.willThrow(new ApiException(AuthErrorCode.INVALID_AUTHORIZATION_CODE));

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.APPLE, request(CODE, null), "device-1"))
				.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_AUTHORIZATION_CODE));

			verify(transactionTemplate, never()).execute(any());
		}

		// 검증: FR-AUTH-12, AC-594-05
		@Test
		void 애플_첫_로그인의_fullName이_닉네임이_된다() {
			stubFirstLogin("김필맵");

			oidcLoginService.login(AuthProvider.APPLE, request(CODE, " 김필맵 "), "device-1");

			assertThat(insertedNickname()).isEqualTo("김필맵");
		}

		// 검증: FR-AUTH-12, AC-594-05
		@Test
		void 이미_있는_계정에_온_fullName은_무시된다() {
			given(appleVerifier.verify(ID_TOKEN, NONCE)).willReturn(info);
			User existing = appleUser(102L, "내가바꾼닉네임");
			given(userRepository.findByProviderAndOid(AuthProvider.APPLE, info.oid()))
				.willReturn(Optional.of(existing));
			given(tokenProvider.issueAccessToken(102L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(102L, "device-1")).willReturn("refresh-token");

			oidcLoginService.login(AuthProvider.APPLE, request(CODE, "김필맵"), "device-1");

			assertThat(existing.getNickname()).isEqualTo("내가바꾼닉네임");
		}

		// 검증: FR-AUTH-12, AC-594-06
		@Test
		void fullName이_없으면_필맵러와_4자리_숫자가_닉네임이_된다() {
			stubFirstLogin("필맵러0000");

			oidcLoginService.login(AuthProvider.APPLE, request(CODE, null), "device-1");

			assertThat(insertedNickname()).matches(DEFAULT_NICKNAME_PATTERN);
		}

		// 검증: FR-AUTH-12, AC-594-06, D-11
		@Test
		void fullName이_20자를_넘으면_기본_닉네임이다() {
			stubFirstLogin("필맵러0000");

			oidcLoginService.login(AuthProvider.APPLE, request(CODE, "가".repeat(21)), "device-1");

			assertThat(insertedNickname()).matches(DEFAULT_NICKNAME_PATTERN);
		}

		// 검증: FR-AUTH-12, AC-594-09
		@Test
		void 애플_요청에_인가_코드가_없으면_DB_접근_없이_2423이다() {
			given(appleVerifier.verify(ID_TOKEN, NONCE)).willReturn(info);

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.APPLE, request(" ", null), "device-1"))
				.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_AUTHORIZATION_CODE));

			verifyNoInteractions(userRepository, appleTokenClient, transactionTemplate);
		}

		// 검증: FR-AUTH-12, AC-594-09
		@Test
		void 교환이_실패하면_계정이_만들어지지_않는다() {
			given(appleVerifier.verify(ID_TOKEN, NONCE)).willReturn(info);
			given(userRepository.findByProviderAndOid(AuthProvider.APPLE, info.oid())).willReturn(Optional.empty());
			given(appleTokenClient.exchange(CODE)).willThrow(new ApiException(AuthErrorCode.OAUTH_PROVIDER_ERROR));

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.APPLE, request(CODE, null), "device-1"))
				.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.OAUTH_PROVIDER_ERROR));

			verify(transactionTemplate, never()).execute(any());
			verify(userRepository, never()).insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any());
		}

		// 검증: FR-AUTH-12, D-8
		@Test
		@DisplayName("동시 첫 로그인에서 패자는 승자 행에 자기 암호문을 덮어쓴다 — 마지막 쓰기가 남는 무해한 경합")
		void 동시_첫_로그인에서_패자는_승자_행에_자기_암호문을_덮어쓴다() {
			User winner = stubFirstLogin("김필맵");
			winner.storeAppleRefreshToken("ENC(winner)");
			given(userRepository.insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any())).willReturn(0);
			given(appleRefreshTokenCipher.encrypt(PLAIN_REFRESH)).willReturn("ENC(loser)");

			oidcLoginService.login(AuthProvider.APPLE, request(CODE, "김필맵"), "device-1");

			assertThat(storedAppleToken(winner)).isEqualTo("ENC(loser)");
		}

		// 검증: FR-AUTH-12, AC-594-07
		@Test
		void 애플_이메일이_다른_계정에_있으면_1409이다() {
			given(appleVerifier.verify(ID_TOKEN, NONCE)).willReturn(info);
			given(userRepository.findByProviderAndOid(AuthProvider.APPLE, info.oid())).willReturn(Optional.empty());
			given(appleTokenClient.exchange(CODE))
				.willReturn(new AppleTokenClient.Exchanged(PLAIN_REFRESH, EXCHANGED_ID_TOKEN));
			given(appleVerifier.verifySubject(EXCHANGED_ID_TOKEN)).willReturn(info.oid());
			given(appleRefreshTokenCipher.encrypt(PLAIN_REFRESH)).willReturn(ENCRYPTED);
			given(userRepository.existsByEmail(info.email())).willReturn(true);

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.APPLE, request(CODE, null), "device-1"))
				.satisfies(thrown -> assertErrorCode(thrown, UserErrorCode.EMAIL_ALREADY_EXISTS));

			verify(userRepository, never()).insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any());
		}

		// 검증: FR-AUTH-12, AC-594-03
		@Test
		void nonce는_검증기에_그대로_전달된다() {
			given(appleVerifier.verify(ID_TOKEN, NONCE)).willThrow(new ApiException(AuthErrorCode.INVALID_ID_TOKEN));

			assertThatThrownBy(() -> oidcLoginService.login(AuthProvider.APPLE, request(CODE, null), "device-1"))
				.satisfies(thrown -> assertErrorCode(thrown, AuthErrorCode.INVALID_ID_TOKEN));

			verify(appleVerifier).verify(ID_TOKEN, NONCE);
			verifyNoInteractions(userRepository, appleTokenClient);
		}
	}

	@Nested
	@DisplayName("issueForOidcUser — dev 모의 로그인 (FR-12)")
	class IssueForOidcUser {

		// 검증: FR-AUTH-12, AC-594-12
		@Test
		void issueForOidcUser는_교환_없이_계정을_만든다() {
			OidcUserInfo info = new OidcUserInfo("dev-apple-1", "dev-apple-1@dev.local", "dev-apple-1");
			User winner = User.createOAuthUser(AuthProvider.APPLE, info.oid(), info.email(), info.nickname());
			ReflectionTestUtils.setField(winner, "id", 200L);
			given(userRepository.findByProviderAndOid(AuthProvider.APPLE, info.oid()))
				.willReturn(Optional.empty(), Optional.of(winner));
			given(userRepository.existsByEmail(info.email())).willReturn(false);
			given(userRepository.insertOAuthUserIgnoreConflict(any(), any(), any(), any(), any())).willReturn(1);
			given(tokenProvider.issueAccessToken(200L, UserRole.USER)).willReturn("jwt-token");
			given(refreshTokenService.issue(200L, "device-1")).willReturn("refresh-token");

			LoginResponseDto response = oidcLoginService.issueForOidcUser(AuthProvider.APPLE, info, "device-1");

			assertThat(response.accessToken()).isEqualTo("jwt-token");
			verifyNoInteractions(appleTokenClient, appleRefreshTokenCipher, transactionTemplate);
			assertThat(storedAppleToken(winner)).isNull();
		}
	}
}
