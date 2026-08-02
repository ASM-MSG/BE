package com.msg.fillmap.user.service;

public interface UserService {

	/**
	 * 계정 즉시 물리 삭제 (MSG-205). 트랜잭션 내 [S3 키 수집 → DELETE users(FK CASCADE 연쇄)],
	 * 커밋 이후 best-effort [S3 객체 정리 → 전 디바이스 refresh 소멸 → 액세스 토큰 블랙리스트].
	 *
	 * @param userId      삭제할 본인 계정 (principal 에서 — 경로에 대상 식별자 없음)
	 * @param accessToken Bearer 접두어를 제거한 요청 원문 토큰 — 커밋 후 블랙리스트용 (§D4)
	 */
	void deleteAccount(Long userId, String accessToken);
}
