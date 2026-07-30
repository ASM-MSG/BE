package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.mock.env.MockEnvironment;

import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.oidc.KakaoOidcProperties;

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

	@ParameterizedTest
	@ValueSource(strings = {
		"DB_URL", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST",
		"REDIS_PASSWORD", "KAKAO_CLIENT_ID", "JWT_SECRET", "JWT_REFRESH_SECRET"
	})
	void 필수_env가_리터럴_플레이스홀더면_기동_실패하고_메시지에_변수명이_있다(String envVar) {
		ProdRequiredEnvValidator validator = validator(Map.of(envVar, "${" + envVar + "}"));

		assertThatThrownBy(validator::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(envVar);
	}

	@Test
	void 공백_값도_기동_실패시킨다() {
		ProdRequiredEnvValidator validator = validator(Map.of("REDIS_PASSWORD", " "));

		assertThatThrownBy(validator::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("REDIS_PASSWORD");
	}

	@Test
	void 복수_누락_시_메시지에_전부_나열된다() {
		ProdRequiredEnvValidator validator = validator(Map.of(
			"JWT_SECRET", "${JWT_SECRET}",
			"JWT_REFRESH_SECRET", "${JWT_REFRESH_SECRET}"
		));

		assertThatThrownBy(validator::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_SECRET")
			.hasMessageContaining("JWT_REFRESH_SECRET");
	}

	@Test
	void 실패_메시지에_정상_주입된_시크릿_값은_없다() {
		ProdRequiredEnvValidator validator = validator(Map.of("DB_URL", "${DB_URL}"));

		assertThatThrownBy(validator::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageNotContaining(NORMAL_VALUES.get("DB_PASSWORD"))
			.hasMessageNotContaining(NORMAL_VALUES.get("REDIS_PASSWORD"))
			.hasMessageNotContaining(NORMAL_VALUES.get("JWT_SECRET"))
			.hasMessageNotContaining(NORMAL_VALUES.get("JWT_REFRESH_SECRET"));
	}

	@Test
	void 전부_정상이면_통과한다() {
		ProdRequiredEnvValidator validator = validator(Map.of());

		assertThatCode(validator::afterPropertiesSet)
			.doesNotThrowAnyException();
	}

	@Test
	void 플레이스홀더_시퀀스를_포함한_정상_값은_통과한다() {
		// 완전 일치만 거부 — 정상 값에 "${" 시퀀스가 있을 수 있다 (MSG-244 Codex 2R P2 회귀 방지)
		ProdRequiredEnvValidator validator = validator(Map.of("REDIS_PASSWORD", "abc${def"));

		assertThatCode(validator::afterPropertiesSet)
			.doesNotThrowAnyException();
	}

	/**
	 * 전부-정상 픽스처에서 {@code overrides} 항목만 오염시킨 검증기를 만든다.
	 */
	private ProdRequiredEnvValidator validator(Map<String, String> overrides) {
		Map<String, String> values = new HashMap<>(NORMAL_VALUES);
		values.putAll(overrides);

		DataSourceProperties dataSourceProperties = new DataSourceProperties();
		dataSourceProperties.setUrl(values.get("DB_URL"));
		dataSourceProperties.setUsername(values.get("DB_USERNAME"));
		dataSourceProperties.setPassword(values.get("DB_PASSWORD"));

		DataRedisProperties redisProperties = new DataRedisProperties();
		redisProperties.setHost(values.get("REDIS_HOST"));
		redisProperties.setPassword(values.get("REDIS_PASSWORD"));

		JwtProperties jwtProperties = new JwtProperties(
			values.get("JWT_SECRET"), Duration.ofMinutes(30),
			values.get("JWT_REFRESH_SECRET"), Duration.ofDays(14));

		KakaoOidcProperties kakaoOidcProperties = new KakaoOidcProperties(
			"https://kauth.kakao.com", "https://kauth.kakao.com/.well-known/jwks.json",
			values.get("KAKAO_CLIENT_ID"));

		return new ProdRequiredEnvValidator(
			dataSourceProperties, redisProperties, jwtProperties, kakaoOidcProperties);
	}
}
