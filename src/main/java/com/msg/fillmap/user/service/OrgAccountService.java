package com.msg.fillmap.user.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.OrgEmailChangeRequestDto;
import com.msg.fillmap.user.dto.OrgProfileResponseDto;
import com.msg.fillmap.user.dto.OrgProfileUpdateRequestDto;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.OrgEmailChangeRequestRepository;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 행사 운영자 계정 설정 (MSG-497 FR-23) — 담당자 이름·연락처의 조회와 수정, 아이디 변경 요청 접수.
 *
 * <p>담당자 이름은 {@code users.nickname} 을 재사용한다. ORG 계정의 nickname 이 곧 담당자 이름이라는
 * 해석은 계정 발급(MSG-499)과 공유하는 전제다.
 */
@Service
@RequiredArgsConstructor
public class OrgAccountService {

	private final UserRepository userRepository;
	private final OrgEmailChangeRequestRepository orgEmailChangeRequestRepository;
	private final Clock clock;

	/** 프로덕션 생성자 — clock 을 UTC 로 고정해 Lombok 전체 생성자에 위임한다 (BadgeAwardServiceImpl 선례). */
	@Autowired
	public OrgAccountService(UserRepository userRepository,
		OrgEmailChangeRequestRepository orgEmailChangeRequestRepository) {
		this(userRepository, orgEmailChangeRequestRepository, Clock.systemUTC());
	}

	@Transactional(readOnly = true)
	public OrgProfileResponseDto getProfile(Long userId) {
		return OrgProfileResponseDto.from(findUser(userId));
	}

	@Transactional
	public OrgProfileResponseDto updateProfile(Long userId, OrgProfileUpdateRequestDto request) {
		User user = findUser(userId);
		user.updateOrgContact(request.contactName(), request.contactPhone());
		return OrgProfileResponseDto.from(user);
	}

	/**
	 * 아이디 변경 요청 접수 (관리자 처리는 MSG-499·500 몫). 저장은 UPSERT 한 문장이라 재요청이 대기
	 * 행을 갱신하고(마지막 요청이 유효), 동시 요청 두 건도 부분 유니크 제약 위반 없이 한 행으로 수렴한다.
	 */
	@Transactional
	public void requestEmailChange(Long userId, OrgEmailChangeRequestDto request) {
		User user = findUser(userId);
		if (request.requestedEmail().equals(user.getEmail())) {
			throw new ApiException(UserErrorCode.EMAIL_CHANGE_SAME_AS_CURRENT);
		}
		orgEmailChangeRequestRepository.upsertPending(userId, request.requestedEmail(),
			LocalDateTime.now(clock));
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
	}
}
