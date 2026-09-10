package com.msg.fillmap.auth.oidc;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;

/**
 * provider 별 OIDC ID Token 검증 계약.
 *
 * <p>애플 등 provider 를 추가할 때는 이 인터페이스의 구현체와
 * 해당 provider 전용 {@link org.springframework.security.oauth2.jwt.JwtDecoder} 빈만 추가하면 된다.
 */
public interface OidcIdTokenVerifier {

	AuthProvider supports();

	/**
	 * @param idToken 클라이언트가 provider SDK 로 발급받은 원본 ID Token
	 * @return         서명·발급자·audience 검증을 통과한 provider 유저 정보
	 * @throws ApiException 서명 위조, 만료, issuer/audience 불일치 등 (INVALID_ID_TOKEN)
	 */
	OidcUserInfo verify(String idToken);
}