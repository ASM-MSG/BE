package com.msg.fillmap.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.dto.SignupRequestDto;
import com.msg.fillmap.auth.dto.SignupResponseDto;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

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
}
