package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.jwt.JwtRefreshTokenProvider;

/**
 * MSG-260: prod 필수 env 일괄 기동 검증 (MSG-244 {@code ProdRedisPasswordValidator} 테스트 이전·확장).
 *
 * <p>{@code ${VAR}} 맨 플레이스홀더는 env 미주입 시 기동 실패하지 않는다 — 바인더가 미해석
 * 플레이스홀더를 리터럴로 통과시킨다 (아래 증거 테스트).
 */
class ProdRequiredEnvValidatorTest {

	private static final Map<String, String> NORMAL_VALUES = Map.of(
		"DB_URL", "jdbc:postgresql://prod-db:5432/fillmap",
		"DB_USERNAME", "fillmap-user",
		"DB_PASSWORD", "db-secret-value",
		"REDIS_HOST", "prod-redis",
		"REDIS_PASSWORD", "redis-secret-value",
		"KAKAO_CLIENT_ID", "kakao-client-id-value",
		"JWT_SECRET", "jwt-secret-value",
		"JWT_REFRESH_SECRET", "jwt-refresh-secret-value"
	);

	@Test
	void 바인더는_미해석_플레이스홀더를_리터럴로_통과시킨다() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("spring.data.redis.password", "${REDIS_PASSWORD}");

		DataRedisProperties properties = Binder.get(environment)
			.bind("spring.data.redis", DataRedisProperties.class)
			.get();

		// 예외 없이 리터럴 통과 = 결함 메커니즘의 러너블 문서 (AwsProperties.S3 주석과 동일, MSG-244)
		assertThat(properties.getPassword()).isEqualTo("${REDIS_PASSWORD}");
	}

	@Test
	void resolvePlaceholders는_미주입_변수를_예외_없이_원문_그대로_반환한다() {
		// Environment.getProperty 와 달리 관용 버전 — 검증기가 이 계약에 의존한다 (Codex 1R 수정 전제 확증)
		MockEnvironment environment = new MockEnvironment();

		String resolved = environment.resolvePlaceholders("${JWT_REFRESH_SECRET}");

		assertThat(resolved).isEqualTo("${JWT_REFRESH_SECRET}");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"DB_URL", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST",
		"REDIS_PASSWORD", "KAKAO_CLIENT_ID", "JWT_SECRET", "JWT_REFRESH_SECRET"
	})
	void 필수_env가_미주입이면_기동_실패하고_메시지에_변수명이_있다(String envVar) {
		DefaultListableBeanFactory beanFactory = beanFactoryWithout(envVar);

		assertThatThrownBy(() -> new ProdRequiredEnvValidator().postProcessBeanFactory(beanFactory))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(envVar);
	}

	@Test
	void 공백_값도_기동_실패시킨다() {
		DefaultListableBeanFactory beanFactory = beanFactoryWith(Map.of("REDIS_PASSWORD", " "));

		assertThatThrownBy(() -> new ProdRequiredEnvValidator().postProcessBeanFactory(beanFactory))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("REDIS_PASSWORD");
	}

	@Test
	void 복수_누락_시_메시지에_전부_나열된다() {
		DefaultListableBeanFactory beanFactory = beanFactoryWithout("JWT_SECRET", "JWT_REFRESH_SECRET");

		assertThatThrownBy(() -> new ProdRequiredEnvValidator().postProcessBeanFactory(beanFactory))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_SECRET")
			.hasMessageContaining("JWT_REFRESH_SECRET");
	}

	@Test
	void 실패_메시지에_정상_주입된_시크릿_값은_없다() {
		DefaultListableBeanFactory beanFactory = beanFactoryWithout("DB_URL");

		assertThatThrownBy(() -> new ProdRequiredEnvValidator().postProcessBeanFactory(beanFactory))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageNotContaining(NORMAL_VALUES.get("DB_PASSWORD"))
			.hasMessageNotContaining(NORMAL_VALUES.get("REDIS_PASSWORD"))
			.hasMessageNotContaining(NORMAL_VALUES.get("JWT_SECRET"))
			.hasMessageNotContaining(NORMAL_VALUES.get("JWT_REFRESH_SECRET"));
	}

	@Test
	void 전부_정상이면_통과한다() {
		DefaultListableBeanFactory beanFactory = beanFactoryWith(Map.of());

		assertThatCode(() -> new ProdRequiredEnvValidator().postProcessBeanFactory(beanFactory))
			.doesNotThrowAnyException();
	}

	@Test
	void 플레이스홀더_시퀀스를_포함한_정상_값은_통과한다() {
		// 완전 일치만 거부 — 정상 값에 "${" 시퀀스가 있을 수 있다 (MSG-244 Codex 2R P2 회귀 방지)
		DefaultListableBeanFactory beanFactory = beanFactoryWith(Map.of("REDIS_PASSWORD", "abc${def"));

		assertThatCode(() -> new ProdRequiredEnvValidator().postProcessBeanFactory(beanFactory))
			.doesNotThrowAnyException();
	}

	@Test
	void JWT_REFRESH_SECRET_미주입_시_WeakKeyException이_아니라_검증기_집계_메시지로_실패한다() {
		// Codex 1R P2: JwtRefreshTokenProvider 생성자가 Keys.hmacShaKeyFor()를 즉시 호출하므로
		// 검증기가 일반 싱글턴 단계에 있으면 리터럴 21바이트가 WeakKeyException으로 선점한다.
		// 검증기는 모든 싱글턴 생성 전(BFPP 단계)에 집계 메시지로 실패해야 한다.
		new ApplicationContextRunner()
			.withPropertyValues(
				"spring.profiles.active=prod",
				"jwt.refresh-secret=${JWT_REFRESH_SECRET}",
				"jwt.access-token-ttl=30m",
				"jwt.refresh-token-ttl=14d",
				"DB_URL=" + NORMAL_VALUES.get("DB_URL"),
				"DB_USERNAME=" + NORMAL_VALUES.get("DB_USERNAME"),
				"DB_PASSWORD=" + NORMAL_VALUES.get("DB_PASSWORD"),
				"REDIS_HOST=" + NORMAL_VALUES.get("REDIS_HOST"),
				"REDIS_PASSWORD=" + NORMAL_VALUES.get("REDIS_PASSWORD"),
				"KAKAO_CLIENT_ID=" + NORMAL_VALUES.get("KAKAO_CLIENT_ID"),
				"JWT_SECRET=" + NORMAL_VALUES.get("JWT_SECRET"),
				"jwt.secret=" + NORMAL_VALUES.get("JWT_SECRET"))
			.withUserConfiguration(JwtPropertiesConfig.class, JwtRefreshTokenProvider.class,
				ProdRequiredEnvValidator.class)
			.run(context -> {
				assertThat(context).hasFailed();
				Throwable root = context.getStartupFailure();
				while (root.getCause() != null) {
					root = root.getCause();
				}
				assertThat(root)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("JWT_REFRESH_SECRET");
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(JwtProperties.class)
	static class JwtPropertiesConfig {
	}

	/**
	 * 전부-정상 픽스처에서 {@code overrides} 항목만 오염시킨 environment 를 담은 BeanFactory 를 만든다.
	 */
	private DefaultListableBeanFactory beanFactoryWith(Map<String, String> overrides) {
		Map<String, String> values = new HashMap<>(NORMAL_VALUES);
		values.putAll(overrides);
		return beanFactoryOf(values);
	}

	/**
	 * 전부-정상 픽스처에서 {@code missingEnvVars} 만 미주입(부재) 상태로 만든 BeanFactory 를 만든다.
	 */
	private DefaultListableBeanFactory beanFactoryWithout(String... missingEnvVars) {
		Map<String, String> values = new HashMap<>(NORMAL_VALUES);
		for (String envVar : missingEnvVars) {
			values.remove(envVar);
		}
		return beanFactoryOf(values);
	}

	private DefaultListableBeanFactory beanFactoryOf(Map<String, String> values) {
		MockEnvironment environment = new MockEnvironment();
		values.forEach(environment::setProperty);
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		// prepareBeanFactory 가 BFPP 실행 전에 등록하는 environment 싱글턴을 재현
		beanFactory.registerSingleton("environment", environment);
		return beanFactory;
	}
}
