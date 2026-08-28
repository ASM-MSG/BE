package com.msg.fillmap.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import com.msg.fillmap.auth.exception.AuthErrorCode;
import com.msg.fillmap.global.exception.ApiException;

@Component
public class JwtRefreshTokenProvider implements RefreshTokenProvider {

	private static final String DEVICE_ID_CLAIM = "did";
	private static final String TYPE_CLAIM = "typ";
	private static final String REFRESH_TYPE = "refresh";

	private final JwtProperties jwtProperties;
	private final SecretKey refreshSecretKey;

	public JwtRefreshTokenProvider(JwtProperties jwtProperties) {
		this.jwtProperties = jwtProperties;
		this.refreshSecretKey = Keys.hmacShaKeyFor(jwtProperties.refreshSecret().getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public String issueRefreshToken(Long userId, String deviceId, String jti) {
		Instant now = Instant.now();
		Instant expiration = now.plus(jwtProperties.refreshTokenTtl());
		return Jwts.builder()
			.subject(userId.toString())
			.claim(DEVICE_ID_CLAIM, deviceId)
			.claim(TYPE_CLAIM, REFRESH_TYPE)
			.id(jti)
			.issuedAt(Date.from(now))
			.expiration(Date.from(expiration))
			.signWith(refreshSecretKey)
			.compact();
	}

	@Override
	public RefreshTokenClaims parseRefreshToken(String refreshToken) {
		Claims claims = parseClaims(refreshToken);
		if (!REFRESH_TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
			throw new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN);
		}
		try {
			return new RefreshTokenClaims(
				Long.parseLong(claims.getSubject()),
				claims.get(DEVICE_ID_CLAIM, String.class),
				claims.getId(),
				claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant()
			);
		} catch (NumberFormatException e) {
			throw new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN);
		}
	}

	private Claims parseClaims(String refreshToken) {
		try {
			return Jwts.parser()
				.verifyWith(refreshSecretKey)
				.build()
				.parseSignedClaims(refreshToken)
				.getPayload();
		} catch (ExpiredJwtException e) {
			throw new ApiException(AuthErrorCode.EXPIRED_REFRESH_TOKEN);
		} catch (JwtException | IllegalArgumentException e) {
			throw new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN);
		}
	}
}
