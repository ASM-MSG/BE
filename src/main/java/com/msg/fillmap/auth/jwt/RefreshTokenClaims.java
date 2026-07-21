package com.msg.fillmap.auth.jwt;

/**
 * 리프레시 토큰에서 복원한 claim 묶음 (MSG-135).
 *
 * @param userId   sub claim — 우리 앱 유저 id
 * @param deviceId did claim — 디바이스별 독립 세션 식별자
 * @param jti      jti claim — 로테이션 식별자 (발급마다 새 UUID)
 */
public record RefreshTokenClaims(Long userId, String deviceId, String jti) {
}
