package com.msg.fillmap.auth.oidc;

import java.util.List;

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
		OAuth2TokenValidator<Jwt> withAudience = jwt ->
			jwt.getAudience().contains(properties.clientId())
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(
					new OAuth2Error("invalid_audience", "ID Token audience 가 카카오 clientId 와 일치하지 않습니다", null));

		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(withIssuer, withAudience)));
		return decoder;
	}
}