package com.msg.fillmap.auth.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.jwt.RefreshTokenClaims;
import com.msg.fillmap.auth.jwt.RefreshTokenProvider;
import com.msg.fillmap.auth.jwt.RefreshTokenStore;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 리프레시 토큰 발급·로테이션·재사용 감지 오케스트레이션 (MSG-135).
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

	private final RefreshTokenProvider refreshTokenProvider;
	private final RefreshTokenStore refreshTokenStore;
	private final TokenProvider tokenProvider;
	private final UserRepository userRepository;
	private final JwtProperties jwtProperties;

	/**
	 * 새 리프레시 토큰을 발급하고 세션(jti)을 저장한다. 기존 세션이 있으면 덮어쓴다(로테이션).
	 */
	public String issue(Long userId, String deviceId) {
		String jti = UUID.randomUUID().toString();
		String refreshToken = refreshTokenProvider.issueRefreshToken(userId, deviceId, jti);
		refreshTokenStore.save(userId, deviceId, jti, jwtProperties.refreshTokenTtl());
		return refreshToken;
	}

	/**
	 * 로테이션 + 재사용 감지 (MVP 는 단순 GET→SET, 확정 결정 6).
	 * TTL 은 로테이션마다 2주로 재설정된다 — 슬라이딩 세션 (확정 결정 4).
	 */
	@Transactional(readOnly = true)
	public ReissueResult reissue(String refreshToken) {
		RefreshTokenClaims claims = refreshTokenProvider.parseRefreshToken(refreshToken);
		String storedJti = refreshTokenStore.findJti(claims.userId(), claims.deviceId());
		if (storedJti == null) {
			// 로그아웃·TTL 만료·체인 폐기 → 재로그인 필요
			throw new ApiException(AuthErrorCode.EXPIRED_REFRESH_TOKEN);
		}
		if (!storedJti.equals(claims.jti())) {
			// 이미 회전된 옛 토큰 재사용 → 탈취로 간주, 해당 디바이스 체인 폐기
			refreshTokenStore.delete(claims.userId(), claims.deviceId());
			throw new ApiException(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
		}
		User user = userRepository.findById(claims.userId())
			.orElseThrow(() -> new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));
		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String rotatedRefreshToken = issue(claims.userId(), claims.deviceId());
		return new ReissueResult(accessToken, rotatedRefreshToken, claims.deviceId());
	}

	public void delete(Long userId, String deviceId) {
		refreshTokenStore.delete(userId, deviceId);
	}

	public void deleteAll(Long userId) {
		refreshTokenStore.deleteAll(userId);
	}
}
