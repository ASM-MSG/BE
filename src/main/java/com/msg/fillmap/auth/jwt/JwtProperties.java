package com.msg.fillmap.auth.jwt;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
	@NotBlank String secret,
	@NotNull Duration accessTokenTtl
) {
}
