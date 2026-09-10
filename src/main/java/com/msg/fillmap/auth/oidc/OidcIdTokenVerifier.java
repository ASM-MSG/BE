package com.msg.fillmap.auth.oidc;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.AuthProvider;

/**
 * provider 별 OIDC ID Token 검증 계약.
 *
 * <p>provider 를 추가할 때는 이 인터페이스의 구현체와
 * 해당 provider 전용 {@link org.springframework.security.oauth2.jwt.JwtDecoder} 빈만 추가하면 된다.
 * nonce 대조가 필요한 provider(애플, MSG-594)는 {@code nonce} 인자를 쓰고, 아닌 provider(카카오 앱 경로)는
 * 받기만 하고 무시한다. 기본 메서드로 우회하지 않는 이유: nonce 를 조용히 버리는 기본 구현은 새 provider 가
 * 실수로 대조를 빠뜨리게 만든다.
 */
public interface OidcIdTokenVerifier {

	AuthProvider supports();

	/**
	 * @param idToken 클라이언트가 provider SDK 로 발급받은 원본 ID Token
	 * @param nonce   대조 기대값 원문. null 이면 provider 가 대조를 생략할 수 있다(카카오) —
	 *                대조가 필수인 provider(애플)는 부재를 INVALID_ID_TOKEN 으로 거절한다
	 * @return         서명·발급자·audience 검증을 통과한 provider 유저 정보
	 * @throws ApiException 서명 위조, 만료, issuer/audience 불일치, nonce 불일치 등 (INVALID_ID_TOKEN)
	 */
	OidcUserInfo verify(String idToken, String nonce);
}
