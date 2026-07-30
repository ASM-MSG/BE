package com.msg.fillmap.global.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * prod 필수 env 일괄 기동 검증 (MSG-260, MSG-244 {@code ProdRedisPasswordValidator} 일반화).
 *
 * <p>{@code ${VAR}} 맨 플레이스홀더는 env 미주입 시 기동 실패하지 않는다 —
 * {@code @ConfigurationProperties} 바인딩은 미해석 플레이스홀더를 예외 없이 리터럴로 통과시키고
 * ({@link AwsProperties.S3} 주석 참조), {@code @NotBlank} 도 리터럴이 공백이 아니라 통과시킨다.
 * Lettuce 지연 접속·JWT 서명처럼 첫 사용 시점에야 터지는 대상은 앱이 뜬 것처럼 보이고 로그인·재발급만
 * 나중에 실패한다. 이 빈이 필수 env 전부를 기동 시점에 일괄 검사해 누락 변수명을 명시하며 fail-fast 한다.
 *
 * <p>{@link BeanFactoryPostProcessor} 인 이유 (Codex 1R): 일반 싱글턴 단계({@code InitializingBean})에서는
 * {@code JwtRefreshTokenProvider} 생성자의 {@code Keys.hmacShaKeyFor()}가 리터럴 21바이트를 먼저 받아
 * {@code WeakKeyException} 으로 집계 메시지를 선점할 수 있다 — 초기화 순서 보장이 없다.
 * BFPP 는 모든 일반 싱글턴 생성 전에 실행되므로 프로퍼티 클래스 대신 {@link Environment} 로 검사한다.
 * 이 단계엔 {@code AutowiredAnnotationBeanPostProcessor} 도 미등록이라 생성자 주입이 안 돼
 * {@code beanFactory} 의 {@code environment} 싱글턴({@code prepareBeanFactory} 가 BFPP 전에 등록)을 조회한다.
 * {@code resolvePlaceholders} 는 미해석 플레이스홀더를 예외 없이 원문 그대로 반환한다 (테스트로 확증).
 *
 * <p>{@code S3_BUCKET_VIDEO} 는 {@link AwsProperties} 의 {@code @Pattern} 이 리터럴을 거부해 이미
 * 기동 실패를 보장하므로 여기 등재하지 않는다 (스펙 D1).
 */
@Component
@Profile("prod")
public class ProdRequiredEnvValidator implements BeanFactoryPostProcessor {

	private static final List<String> REQUIRED_ENV_VARS = List.of(
		"DB_URL", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST",
		"REDIS_PASSWORD", "KAKAO_CLIENT_ID", "JWT_SECRET", "JWT_REFRESH_SECRET");

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		Environment environment = beanFactory.getBean(Environment.class);
		List<String> missing = new ArrayList<>();
		for (String envVar : REQUIRED_ENV_VARS) {
			String placeholder = "${" + envVar + "}";
			String resolved = environment.resolvePlaceholders(placeholder);
			// 미해석(원문 그대로) 완전 일치로만 거부 — 정상 값에 "${" 시퀀스가 있을 수 있다 (MSG-244 Codex 2R P2)
			if (!StringUtils.hasText(resolved) || placeholder.equals(resolved)) {
				missing.add(envVar);
			}
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException(
				"prod 필수 환경변수 미설정: " + String.join(", ", missing)
					+ " — 값이 비었거나 미해석 플레이스홀더 리터럴 (MSG-260)");
		}
	}
}
