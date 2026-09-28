package com.msg.fillmap.global.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * 기동 워밍업 프로퍼티 (MSG-608). 설정만 읽으므로 기본 @SpringBootTest 컨텍스트(MsgbeApplicationTests 와 같은 키)를
 * 재사용한다 — 컨텍스트를 새로 늘리지 않는다.
 */
@SpringBootTest
class WarmupPropertiesTest {

	@Autowired
	private Environment environment;

	@Autowired
	private WarmupProperties properties;

	// 검증: NFR-PERF-01, AC-608-04
	@Test
	void 테스트_프로파일에서는_워밍업이_꺼져_있다() {
		assertThat(environment.getProperty("fillmap.warmup.enabled", Boolean.class)).isFalse();
		assertThat(properties.enabled()).isFalse();
	}

	// 검증: NFR-PERF-01, AC-608-01
	// 2,000 은 2026-09-27 dev 실측으로 확정(스펙 D7, Q2). 바꿀 때는 application.yml 과 이 기대값을 함께 고친다.
	@Test
	void iterations_기본값은_확정값이다() {
		assertThat(properties.iterations()).isEqualTo(2000);
	}
}
