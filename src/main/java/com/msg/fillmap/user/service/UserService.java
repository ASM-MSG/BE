package com.msg.fillmap.user.service;

import com.msg.fillmap.user.dto.UserProfileResponseDto;

public interface UserService {

	/**
	 * 계정 즉시 물리 삭제 (MSG-205). 트랜잭션 내 [S3 키 수집 → DELETE users(FK CASCADE 연쇄)],
	 * 커밋 이후 best-effort [S3 객체 정리 → 전 디바이스 refresh 소멸 → 액세스 토큰 블랙리스트].
	 *
	 * @param userId      삭제할 본인 계정 (principal 에서 — 경로에 대상 식별자 없음)
	 * @param accessToken Bearer 접두어를 제거한 요청 원문 토큰 — 커밋 후 블랙리스트용 (§D4)
	 */
	void deleteAccount(Long userId, String accessToken);

	/** 내 프로필 조회 (MSG-203 FR-1). 소셜 로그인이 저장한 email·nickname 을 가공 없이 반환한다. */
	UserProfileResponseDto getMyProfile(Long userId);

	/** 닉네임 변경 (MSG-203 FR-2·5). 변경 후 프로필을 반환한다(§D2). 중복 검사 없음 (FR-6). */
	UserProfileResponseDto updateNickname(Long userId, String nickname);
}
