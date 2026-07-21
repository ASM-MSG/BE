package com.msg.fillmap.auth.jwt;

import java.time.Duration;

/**
 * 리프레시 세션 저장소 계약 (MSG-135).
 *
 * <p>키 = (userId, deviceId), 값 = 현재 유효한 jti. 토큰 원문은 저장하지 않는다.
 * 재사용 감지는 "저장 jti ≠ 제시 jti" 로 판정한다.
 */
public interface RefreshTokenStore {

	void save(Long userId, String deviceId, String jti, Duration ttl);

	/**
	 * @return 현재 유효한 jti, 세션이 없으면(로그아웃/TTL 만료/체인 폐기) null
	 */
	String findJti(Long userId, String deviceId);

	void delete(Long userId, String deviceId);

	/**
	 * 해당 유저의 모든 디바이스 세션을 삭제한다 — 로그아웃 시 X-Device-Id 부재 폴백.
	 */
	void deleteAll(Long userId);
}
