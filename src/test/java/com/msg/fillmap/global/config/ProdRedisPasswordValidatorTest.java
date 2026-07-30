package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.mock.env.MockEnvironment;

/**
 * MSG-244 Codex 교차 리뷰 검증: {@code password: ${REDIS_PASSWORD}} 는 env 미주입 시
 * 기동 실패(fail-fast)하지 않는다 — 바인더가 미해석 플레이스홀더를 리터럴로 통과시킨다.
 */
class ProdRedisPasswordValidatorTest {

	@Test
	void 바인더는_미해석_REDIS_PASSWORD_플레이스홀더를_리터럴로_통과시킨다() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("spring.data.redis.password", "${REDIS_PASSWORD}");

		DataRedisProperties properties = Binder.get(environment)
			.bind("spring.data.redis", DataRedisProperties.class)
			.get();

		// 예외 없이 리터럴 통과 = Codex 주장 사실 (AwsProperties.S3 주석과 동일 메커니즘)
		assertThat(properties.getPassword()).isEqualTo("${REDIS_PASSWORD}");
	}

	@Test
	void 리터럴_플레이스홀더_비밀번호는_기동_실패시킨다() {
		DataRedisProperties properties = new DataRedisProperties();
		properties.setPassword("${REDIS_PASSWORD}");

		assertThatThrownBy(new ProdRedisPasswordValidator(properties)::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("REDIS_PASSWORD");
	}

	@Test
	void 공백_비밀번호도_기동_실패시킨다() {
		DataRedisProperties properties = new DataRedisProperties();
		properties.setPassword(" ");

		assertThatThrownBy(new ProdRedisPasswordValidator(properties)::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 정상_비밀번호는_통과한다() {
		DataRedisProperties properties = new DataRedisProperties();
		properties.setPassword("secret123");

		assertThatCode(new ProdRedisPasswordValidator(properties)::afterPropertiesSet)
			.doesNotThrowAnyException();
	}
}
