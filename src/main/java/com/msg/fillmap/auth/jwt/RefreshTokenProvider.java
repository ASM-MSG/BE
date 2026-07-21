package com.msg.fillmap.auth.jwt;

import com.msg.fillmap.global.exception.ApiException;

/**
 * 리프레시 토큰 발급/검증 계약 (MSG-135).
 *
 * <p>액세스 토큰({@link TokenProvider})과 분리된 컴포넌트다. 서명 키도 분리(jwt.refresh-secret)하고
 * {@code typ=refresh} claim 으로 액세스↔리프레시 상호 오용을 차단한다.
 */
public interface RefreshTokenProvider {

	/**
	 * 리프레시 토큰(JWT)을 발급한다.
	 *
	 * @param userId   우리 앱 유저 id (sub claim)
	 * @param deviceId 디바이스 식별자 (did claim)
	 * @param jti      로테이션 식별자 — 호출자가 발급마다 새 UUID 를 넘긴다 (jti claim)
	 * @return         Base64URL 로 인코딩된 JWT 문자열
	 */
	String issueRefreshToken(Long userId, String deviceId, String jti);

	/**
	 * 리프레시 토큰을 검증하고 claim 을 복원한다.
	 *
	 * @param refreshToken 순수 토큰 문자열
	 * @return userId + deviceId + jti
	 * @throws ApiException 만료(EXPIRED_REFRESH_TOKEN) 또는 위조·형식오류·typ 불일치(INVALID_REFRESH_TOKEN)
	 */
	RefreshTokenClaims parseRefreshToken(String refreshToken);
}
