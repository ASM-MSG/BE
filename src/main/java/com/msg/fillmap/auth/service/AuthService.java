package com.msg.fillmap.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.dto.LoginRequestDto;
import com.msg.fillmap.auth.dto.LoginResponseDto;
import com.msg.fillmap.auth.dto.SignupRequestDto;
import com.msg.fillmap.auth.dto.SignupResponseDto;
import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final TokenProvider tokenProvider;
	private final RefreshTokenService refreshTokenService;

	@Transactional
	public SignupResponseDto signup(SignupRequestDto request) {
		if (userRepository.existsByEmail(request.email())) {
			throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS);
		}
		String encodedPassword = passwordEncoder.encode(request.password());
		User user = User.createLocalUser(request.email(), encodedPassword, request.nickname());
		User saved = userRepository.save(user);
		return SignupResponseDto.from(saved);
	}

	@Transactional(readOnly = true)
	public LoginResponseDto login(LoginRequestDto request, String deviceId) {
		User user = userRepository.findByEmail(request.email())
			.orElseThrow(() -> new ApiException(AuthErrorCode.INVALID_CREDENTIALS));
		// 소셜 유저(passwordHash null)도 matches 가 false 를 리턴해서 자연스럽게 INVALID_CREDENTIALS
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new ApiException(AuthErrorCode.INVALID_CREDENTIALS);
		}
		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenService.issue(user.getId(), deviceId);
		return new LoginResponseDto(accessToken, refreshToken);
	}

	@Transactional
	public void logout(String accessToken, String deviceId) {
		// 필터가 검증한 것과 같은 토큰이라 principal 재파싱으로 userId 를 얻는다 (SecurityContext 와 동일 값)
		AuthPrincipal principal = tokenProvider.parseAccessToken(accessToken);
		tokenProvider.invalidateAccessToken(accessToken);
		if (deviceId == null || deviceId.isBlank()) {
			// X-Device-Id 없음 → 로그아웃-올 폴백 (MSG-135 확정 결정 5)
			refreshTokenService.deleteAll(principal.userId());
			return;
		}
		refreshTokenService.delete(principal.userId(), deviceId);
	}
}
