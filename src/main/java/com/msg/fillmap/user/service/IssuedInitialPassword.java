package com.msg.fillmap.user.service;

import java.time.LocalDateTime;

/**
 * 발급 트랜잭션이 만든 초기 비밀번호 한 벌 (MSG-499). 커밋 후 발송 단계까지만 살아 있는 값이라
 * 저장·응답 어디에도 실리지 않는다.
 *
 * <p>{@code toString} 을 덮어 평문을 지운다 — 레코드 기본 구현은 모든 필드를 찍으므로, 이 객체가
 * 로그 파라미터나 예외 메시지에 한 번이라도 섞이면 평문 비노출(FR-2)이 그 자리에서 깨진다.
 */
record IssuedInitialPassword(Long userId, String email, String plainPassword, LocalDateTime issuedAt) {

	@Override
	public String toString() {
		return "IssuedInitialPassword[userId=" + userId + ", issuedAt=" + issuedAt + "]";
	}
}
