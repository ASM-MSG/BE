package com.msg.fillmap.user.service;

import com.msg.fillmap.user.dto.ProfileImagePresignRequestDto;
import com.msg.fillmap.user.dto.ProfileImagePresignResponseDto;
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

	/**
	 * 프로필 이미지 업로드용 presigned URL 발급 (MSG-373 FR-1). 발급 시점에 확장자·Content-Type 쌍과
	 * 선언 크기를 검증하고, 서명에 둘을 넣어 선언과 다르게 올리면 S3 가 403 을 내게 한다.
	 * 발급 키는 `profiles/pending/{userId}/{uuid}.{ext}` 로 확정 전까지 라이프사이클 만료 대상이다.
	 */
	ProfileImagePresignResponseDto issueProfileImagePresignedUrl(Long userId, ProfileImagePresignRequestDto request);

	/**
	 * 프로필 이미지 변경 확정 (MSG-373 FR-1). pending 키의 소유·실존·실측 크기를 재검증한 뒤
	 * original 로 복사하고 공개 URL 을 저장한다. 이전 이미지 객체는 커밋 후 best-effort 로 정리한다.
	 */
	UserProfileResponseDto updateProfileImage(Long userId, String s3Key);

	/** 프로필 이미지 제거 (MSG-373 FR-6). 기본 상태(null)로 되돌린다 — 이미 기본 상태여도 성공(멱등). */
	UserProfileResponseDto removeProfileImage(Long userId);

	/**
	 * 위치정보 사용 동의 변경 (MSG-402 FR-2·3·4). 온보딩 동의 제출과 프로필 편집 토글이 공용으로 쓴다.
	 * 값이 실제로 달라질 때만 변경 시각이 갱신되고, 같은 값 재저장은 성공하되 시각이 그대로다(멱등).
	 * 변경 후 프로필을 반환한다 — 닉네임 수정과 같은 형태라 FE 가 재조회 없이 토글 상태를 확정한다(§D-1).
	 */
	UserProfileResponseDto updateLocationConsent(Long userId, boolean consented);
}
