package com.msg.fillmap.global.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.oidc.KakaoOidcProperties;

/**
 * prod 필수 env 일괄 기동 검증 (MSG-260, MSG-244 {@code ProdRedisPasswordValidator} 일반화).
 *
 * <p>{@code ${VAR}} 맨 플레이스홀더는 env 미주입 시 기동 실패하지 않는다 —
 * {@code @ConfigurationProperties} 바인딩은 미해석 플레이스홀더를 예외 없이 리터럴로 통과시키고
 * ({@link AwsProperties.S3} 주석 참조), {@code @NotBlank} 도 리터럴이 공백이 아니라 통과시킨다.
 * Lettuce 지연 접속·JWT 서명처럼 첫 사용 시점에야 터지는 대상은 앱이 뜬 것처럼 보이고 로그인·재발급만
 * 나중에 실패한다. 이 빈이 필수 env 전부를 기동 시점에 일괄 검사해 누락 변수명을 명시하며 fail-fast 한다.
 *
 * <p>{@code S3_BUCKET_VIDEO} 는 {@link AwsProperties} 의 {@code @Pattern} 이 리터럴을 거부해 이미
 * 기동 실패를 보장하므로 여기 등재하지 않는다 (스펙 D1).
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProdRequiredEnvValidator implements InitializingBean {

	private final DataSourceProperties dataSourceProperties;
	private final DataRedisProperties redisProperties;
	private final JwtProperties jwtProperties;
	private final KakaoOidcProperties kakaoOidcProperties;

	@Override
	public void afterPropertiesSet() {
		List<String> missing = new ArrayList<>();
		check(missing, "DB_URL", dataSourceProperties.getUrl());
		check(missing, "DB_USERNAME", dataSourceProperties.getUsername());
		check(missing, "DB_PASSWORD", dataSourceProperties.getPassword());
		check(missing, "REDIS_HOST", redisProperties.getHost());
		check(missing, "REDIS_PASSWORD", redisProperties.getPassword());
		check(missing, "KAKAO_CLIENT_ID", kakaoOidcProperties.clientId());
		check(missing, "JWT_SECRET", jwtProperties.secret());
		check(missing, "JWT_REFRESH_SECRET", jwtProperties.refreshSecret());
		if (!missing.isEmpty()) {
			throw new IllegalStateException(
				"prod 필수 환경변수 미설정: " + String.join(", ", missing)
					+ " — 값이 비었거나 미해석 플레이스홀더 리터럴 (MSG-260)");
		}
	}

	private void check(List<String> missing, String envVar, String value) {
		// 미해석 리터럴 완전 일치로만 거부 — 정상 값에 "${" 시퀀스가 있을 수 있다 (MSG-244 Codex 2R P2)
		if (!StringUtils.hasText(value) || ("${" + envVar + "}").equals(value)) {
			missing.add(envVar);
		}
	}
}
