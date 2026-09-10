package com.msg.fillmap.auth.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.auth.jwt.InvalidatedTokenStore;
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
	private final InvalidatedTokenStore invalidatedTokenStore;

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
		rejectIfInvalidatedByReset(claims);
		return new ReissueResult(accessToken, rotatedRefreshToken, claims.deviceId());
	}

	/**
	 * 비밀번호 재설정으로 무효화된 리프레시의 재발급 차단 (MSG-497). 검사가 회전 save <b>뒤</b>인 것과
	 * 재설정이 "무효화 기록을 세션 전량 삭제보다 먼저" 쓰는 것이 조합되면 어느 인터리빙에서도 부활
	 * 세션이 남지 않는다 — save 가 삭제보다 앞이면 삭제가 그 세션을 지우고, 뒤면 그보다 앞선 무효화
	 * 기록이 이 검사에 반드시 보인다. 위반이면 방금 저장한 세션을 지우고 재로그인을 강제한다.
	 *
	 * <p>재설정 후 재로그인으로 받은 리프레시는 iat 가 무효화 시각 이후라 통과한다. 액세스 검사와 달리
	 * 역할 스코프를 두지 않는 것은 재발급이 저빈도(디바이스당 액세스 만료 주기)라서다.
	 */
	private void rejectIfInvalidatedByReset(RefreshTokenClaims claims) {
		Long invalidatedAtEpochSecond = invalidatedTokenStore.findUserInvalidatedAtEpochSecond(claims.userId());
		if (invalidatedAtEpochSecond == null) {
			return;
		}
		// 같은 초 포함 거부 — 액세스 토큰 판정과 같은 fail-closed 경계다 (iat 가 없는 토큰도 무효).
		if (claims.issuedAt() == null || claims.issuedAt().getEpochSecond() <= invalidatedAtEpochSecond) {
			refreshTokenStore.delete(claims.userId(), claims.deviceId());
			throw new ApiException(AuthErrorCode.EXPIRED_REFRESH_TOKEN);
		}
	}

	public void delete(Long userId, String deviceId) {
		refreshTokenStore.delete(userId, deviceId);
	}

	public void deleteAll(Long userId) {
		refreshTokenStore.deleteAll(userId);
	}
}
