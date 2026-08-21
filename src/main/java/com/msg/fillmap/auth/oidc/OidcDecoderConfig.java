package com.msg.fillmap.auth.oidc;

import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * provider 별 OIDC ID Token 검증용 {@link JwtDecoder} 를 조립한다.
 *
 * <p>Spring Security 의 resource-server 필터체인({@code .oauth2ResourceServer(...)})에는
 * 연결하지 않는다. 우리 앱 자체 API 인증은 {@link com.msg.fillmap.auth.jwt.JwtAuthenticationFilter} 가 전담하고,
 * 여기서는 오직 외부 provider ID Token 검증에 필요한 JWKS 조회·서명검증 기능만 재사용한다.
 */
@Configuration
public class OidcDecoderConfig {

	@Bean
	public JwtDecoder kakaoJwtDecoder(KakaoOidcProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();

		OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(properties.issuer());
		OAuth2TokenValidator<Jwt> withAudience =
			audienceValidator(Set.of(properties.clientId(), properties.appClientId()));

		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(withIssuer, withAudience)));
		return decoder;
	}

	/**
	 * 카카오는 인가를 요청한 앱 키를 그대로 aud 에 넣는다 — 같은 카카오 애플리케이션이라도
	 * 웹(REST API 키)과 앱(네이티브 SDK 의 네이티브 앱 키)의 aud 가 갈리므로 두 키를 모두 허용한다 (MSG-452).
	 */
	static OAuth2TokenValidator<Jwt> audienceValidator(Set<String> allowedAudiences) {
		return jwt -> jwt.getAudience().stream().anyMatch(allowedAudiences::contains)
			? OAuth2TokenValidatorResult.success()
			: OAuth2TokenValidatorResult.failure(
				new OAuth2Error("invalid_audience", "ID Token audience 가 허용된 카카오 앱 키가 아닙니다", null));
	}
}