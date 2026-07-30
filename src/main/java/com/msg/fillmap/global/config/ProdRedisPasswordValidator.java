package com.msg.fillmap.global.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

/**
 * prod Redis 비밀번호 기동 검증 (MSG-244).
 *
 * <p>{@code password: ${REDIS_PASSWORD}} 는 env 미주입 시 기동 실패하지 않는다 —
 * {@code @ConfigurationProperties} 바인딩은 미해석 플레이스홀더를 예외 없이 리터럴로 통과시키고
 * ({@link AwsProperties.S3} 주석 참조), Lettuce 는 첫 사용 시에야 인증하므로 앱은 뜬 것처럼 보이고
 * 로그인·재발급·로그아웃만 나중에 터진다. 이 빈이 그 경우를 기동 시점에 잡는다.
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProdRedisPasswordValidator implements InitializingBean {

	private final DataRedisProperties redisProperties;

	@Override
	public void afterPropertiesSet() {
		String password = redisProperties.getPassword();
		// 미해석 리터럴 완전 일치로만 거부 — 정상 비밀번호에 "${" 시퀀스가 있을 수 있다 (Codex 2R P2)
		if (!StringUtils.hasText(password) || "${REDIS_PASSWORD}".equals(password)) {
			throw new IllegalStateException(
				"spring.data.redis.password 미설정 — REDIS_PASSWORD 환경변수를 주입해야 한다 (redis-prod requirepass, MSG-244)");
		}
	}
}
