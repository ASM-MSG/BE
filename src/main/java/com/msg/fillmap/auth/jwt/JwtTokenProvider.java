package com.msg.fillmap.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

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
	/**
	 * 사용자 단위 무효화 검사를 받는 역할 (MSG-497). 추가 Redis 왕복 1회가 붙는 대상을 콘솔·관리자
	 * 트래픽으로 한정한다. 이 스코프가 완결적인 이유는 재설정 대상 자체가 ORG·ADMIN 의 LOCAL 계정으로
	 * 좁혀져 있어서다 — "재설정했는데 무효화 검사 밖인 토큰"이 구조적으로 없다.
	 */
	private static final Set<UserRole> USER_INVALIDATION_CHECKED_ROLES = Set.of(UserRole.ORG, UserRole.ADMIN);

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
			if (USER_INVALIDATION_CHECKED_ROLES.contains(role) && isUserInvalidated(userId, claims)) {
				throw new ApiException(AuthErrorCode.INVALID_TOKEN);
			}
			return new AuthPrincipal(userId, role);
		} catch (ApiException e) {
			throw e;
		} catch (IllegalArgumentException e) {
			throw new ApiException(AuthErrorCode.INVALID_TOKEN);
		}
	}

	/**
	 * 발급 시각(iat)이 사용자 단위 무효화 시각과 <b>같거나 이르면</b> 무효다 (MSG-497). 엄격 미만
	 * 비교면 무효화와 같은 초에 발급돼 있던 기존 토큰이 살아남는 구멍이 생긴다 — 보안 경계는 오탐
	 * (정상 토큰 1회 거부) 쪽으로 넘어지게 둔다. iat 가 없는 토큰도 같은 이유로 무효로 본다.
	 */
	private boolean isUserInvalidated(Long userId, Claims claims) {
		Long invalidatedAtEpochSecond = invalidatedTokenStore.findUserInvalidatedAtEpochSecond(userId);
		if (invalidatedAtEpochSecond == null) {
			return false;
		}
		Date issuedAt = claims.getIssuedAt();
		return issuedAt == null || issuedAt.toInstant().getEpochSecond() <= invalidatedAtEpochSecond;
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
