package com.msg.fillmap.auth.jwt;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 우리 앱의 access token 발급/검증 계약.
 *
 * <p>이메일 로그인, 카카오/애플 등 소셜 OIDC 로그인 등 어떤 인증 흐름이든
 * 최종적으로 User 를 확정한 뒤 {@link #issueAccessToken(Long, UserRole)} 를 호출해
 * 클라이언트에 반환할 토큰을 얻는다.
 *
 * <p>외부 OIDC ID Token (예: 카카오 ID Token) 은 이 인터페이스가 다루지 않는다.
 * ID Token 검증은 각 소셜 provider 담당자가 직접 처리한다.
 */
public interface TokenProvider {

	/**
	 * 우리 앱 access token 을 발급한다.
	 *
	 * @param userId 우리 앱 유저 id (subject 로 사용)
	 * @param role   유저 역할 (role claim)
	 * @return       Base64URL 로 인코딩된 JWT 문자열
	 */
	String issueAccessToken(Long userId, UserRole role);

	/**
	 * 요청에서 파싱한 access token 을 검증하고 principal 정보를 리턴한다.
	 * (JWT 인증 필터가 사용. 소셜 로그인 담당자는 이 메서드를 직접 호출할 일이 없다.)
	 *
	 * @param accessToken Bearer 접두어를 제거한 순수 토큰 문자열
	 * @return userId + role
	 * @throws ApiException 만료(EXPIRED_TOKEN) 또는 위조·형식오류(INVALID_TOKEN)
	 */
	AuthPrincipal parseAccessToken(String accessToken);
}
