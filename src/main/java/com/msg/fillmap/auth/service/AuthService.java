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
	public LoginResponseDto login(LoginRequestDto request) {
		User user = userRepository.findByEmail(request.email())
			.orElseThrow(() -> new ApiException(AuthErrorCode.INVALID_CREDENTIALS));
		// 소셜 유저(passwordHash null)도 matches 가 false 를 리턴해서 자연스럽게 INVALID_CREDENTIALS
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new ApiException(AuthErrorCode.INVALID_CREDENTIALS);
		}
		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		return new LoginResponseDto(accessToken);
	}

	@Transactional
	public void logout(String accessToken) {
		tokenProvider.invalidateAccessToken(accessToken);
	}
}
