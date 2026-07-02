package com.msg.fillmap.auth.oidc;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "oauth.kakao")
public record KakaoOidcProperties(
	@NotBlank String issuer,
	@NotBlank String jwkSetUri,
	@NotBlank String clientId
) {
}