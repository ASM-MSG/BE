package com.msg.fillmap.auth.jwt;

import java.time.Duration;
import java.time.Instant;

public interface InvalidatedTokenStore {

	void invalidate(String token, Instant expiresAt);

	boolean isInvalidated(String token);

	/**
	 * 사용자 단위 토큰 무효화 기록 (MSG-497). 이 시각과 같거나 이르게 발급된 그 사용자의 토큰을 전부
	 * 무효로 만든다 — 비밀번호 재설정처럼 이미 쥔 액세스 토큰까지 끊어야 하는 복구 흐름이 쓴다.
	 *
	 * <p>TTL 은 <b>리프레시 토큰 수명</b>이다(액세스 수명이 아니다). 커밋 후 세션 전량 삭제가 부분
	 * 실패하면 살아남은 리프레시가 2주를 사는데, 마커가 1시간에 사라지면 그 뒤의 재발급 검사가 통과해
	 * 도난 리프레시가 부활한다. 마커가 리프레시 수명만큼 살면 재시도나 영속화 없이도 그 부활이
	 * 구조적으로 막힌다.
	 */
	void invalidateUser(Long userId, Instant invalidatedAt, Duration ttl);

	/**
	 * @return 사용자 단위 무효화 시각(epoch 초), 기록이 없으면 null. 판정은 호출부의
	 *     "발급 시각(iat)이 이 값과 같거나 이르면 거부"다 — 초 단위 경계를 오탐 쪽(fail-closed)으로 둔다.
	 */
	Long findUserInvalidatedAtEpochSecond(Long userId);
}
