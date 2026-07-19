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
		if (userRepository.existsByEmail(info.email())) {
			throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS);
		}
		return userRepository.save(User.createOAuthUser(provider, info.oid(), info.email(), info.nickname()));
	}
}