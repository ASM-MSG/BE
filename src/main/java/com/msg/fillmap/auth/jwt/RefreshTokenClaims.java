package com.msg.fillmap.auth.jwt;

import java.time.Instant;

/**
 * 리프레시 토큰에서 복원한 claim 묶음 (MSG-135).
 *
 * @param userId   sub claim — 우리 앱 유저 id
 * @param deviceId did claim — 디바이스별 독립 세션 식별자
 * @param jti      jti claim — 로테이션 식별자 (발급마다 새 UUID)
 * @param issuedAt iat claim — 발급 시각. 사용자 단위 무효화(MSG-497)와 대조해 재설정 이전에 발급된
 *                 리프레시로는 재발급이 성립하지 않게 한다. 발급 때부터 실려 있던 값을 파서가 버리고
 *                 있던 것을 복원한 것이라 토큰 형식 변경이 아니다.
 */
public record RefreshTokenClaims(Long userId, String deviceId, String jti, Instant issuedAt) {
}
