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
import com.msg.fillmap.user.entity.UserRole;

@Component
public class JwtTokenProvider implements TokenProvider {

	private static final String ROLE_CLAIM = "role";

	private final JwtProperties jwtProperties;
	private final SecretKey secretKey;
	private final InvalidatedTokenStore invalidatedTokenStore;

	public JwtTokenProvider(JwtProperties jwtProperties, InvalidatedTokenStore invalidatedTokenStore) {
		this.jwtProperties = jwtProperties;
		this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
		this.invalidatedTokenStore = invalidatedTokenStore;
	}

	@Override
	public String issueAccessToken(Long userId, UserRole role) {
		Instant now = Instant.now();
		Instant expiration = now.plus(jwtProperties.accessTokenTtl());
		return Jwts.builder()
			.subject(userId.toString())
			.claim(ROLE_CLAIM, role.name())
			.issuedAt(Date.from(now))
			.expiration(Date.from(expiration))
			.signWith(secretKey)
			.compact();
	}

	@Override
	public void invalidateAccessToken(String accessToken) {
		Claims claims = parseClaims(accessToken);
		invalidatedTokenStore.invalidate(accessToken, claims.getExpiration().toInstant());
	}

	@Override
	public AuthPrincipal parseAccessToken(String accessToken) {
		try {
			Claims claims = parseClaims(accessToken);
			if (invalidatedTokenStore.isInvalidated(accessToken)) {
				throw new ApiException(AuthErrorCode.INVALID_TOKEN);
			}
			Long userId = Long.parseLong(claims.getSubject());
			UserRole role = UserRole.valueOf(claims.get(ROLE_CLAIM, String.class));
			return new AuthPrincipal(userId, role);
		} catch (ApiException e) {
			throw e;
		} catch (IllegalArgumentException e) {
			throw new ApiException(AuthErrorCode.INVALID_TOKEN);
		}
	}

	private Claims parseClaims(String accessToken) {
		try {
			return Jwts.parser()
				.verifyWith(secretKey)
				.build()
				.parseSignedClaims(accessToken)
				.getPayload();
		} catch (ExpiredJwtException e) {
			throw new ApiException(AuthErrorCode.EXPIRED_TOKEN);
		} catch (JwtException | IllegalArgumentException e) {
			throw new ApiException(AuthErrorCode.INVALID_TOKEN);
		}
	}
}
