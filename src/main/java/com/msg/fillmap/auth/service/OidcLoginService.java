package com.msg.fillmap.auth.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.oidc.OidcIdTokenVerifier;
import com.msg.fillmap.auth.oidc.OidcUserInfo;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

@Service
public class OidcLoginService {

	private final UserRepository userRepository;
	private final TokenProvider tokenProvider;
	private final RefreshTokenService refreshTokenService;
	private final Map<AuthProvider, OidcIdTokenVerifier> verifiers;

	public OidcLoginService(
		UserRepository userRepository,
		TokenProvider tokenProvider,
		RefreshTokenService refreshTokenService,
		List<OidcIdTokenVerifier> verifiers
	) {
		this.userRepository = userRepository;
		this.tokenProvider = tokenProvider;
		this.refreshTokenService = refreshTokenService;
		this.verifiers = verifiers.stream()
			.collect(Collectors.toUnmodifiableMap(OidcIdTokenVerifier::supports, Function.identity()));
	}

	@Transactional
	public LoginResponseDto login(AuthProvider provider, String idToken, String deviceId) {
		OidcIdTokenVerifier verifier = verifiers.get(provider);
		if (verifier == null) {
			throw new ApiException(AuthErrorCode.UNSUPPORTED_PROVIDER);
		}

		OidcUserInfo info = verifier.verify(idToken);
		return issueForOidcUser(provider, info, deviceId);
	}

	/**
	 * OIDC 사용자 정보로 find-or-create 후 액세스+리프레시 토큰을 발급한다 (MSG-135).
	 * ID Token 검증을 마친 뒤 공유하는 발급 로직 — [로컬/dev] 소셜 로그인 모의(DevAuthController)도 재사용한다.
	 */
	@Transactional
	public LoginResponseDto issueForOidcUser(AuthProvider provider, OidcUserInfo info, String deviceId) {
		User user = userRepository.findByProviderAndOid(provider, info.oid())
			.orElseGet(() -> registerNewUser(provider, info));

		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenService.issue(user.getId(), deviceId);
		return new LoginResponseDto(accessToken, refreshToken);
	}

	private User registerNewUser(AuthProvider provider, OidcUserInfo info) {
		// email 은 카카오에서 받지 않아 null 일 수 있다 (MSG-310) — null 이면 중복 검사 없이 그대로 저장한다.
		if (info.email() != null && userRepository.existsByEmail(info.email())) {
			throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS);
		}
		// 동시 첫 로그인 경합 (Codex 지적): 두 요청이 모두 부재를 관측해도 ON CONFLICT 무삽입이라
		// UNIQUE 위반 500 이 없다 — 삽입 후 재조회가 승자 행이면 그걸로 토큰을 발급한다(패자 회수).
		// 팩토리를 거치는 건 friend_code 생성 로직을 엔티티 한 곳에 유지하기 위해서다 (NOT NULL, DB DEFAULT 없음).
		User candidate = User.createOAuthUser(provider, info.oid(), info.email(), info.nickname());
		userRepository.insertOAuthUserIgnoreConflict(
			provider.name(), info.oid(), info.email(), info.nickname(), candidate.getFriendCode());
		return userRepository.findByProviderAndOid(provider, info.oid())
			// oid 재조회 부재 = 삽입이 email 충돌로 무효된 것 (다른 계정이 같은 이메일을 선점)
			.orElseThrow(() -> new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS));
	}
}