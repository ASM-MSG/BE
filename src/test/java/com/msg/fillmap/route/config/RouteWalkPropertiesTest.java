package com.msg.fillmap.route.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RouteWalkProperties")
class RouteWalkPropertiesTest {

	private static final String BASE_URL = "https://apis.openapi.sk.com";
	private static final Duration TIMEOUT = Duration.ofSeconds(3);

	// 검증: NFR-SEC-10
	@Test
	void 플래그를_켜고_appKey가_비면_기동이_실패한다() {
		assertThatIllegalStateException()
			.isThrownBy(() -> new RouteWalkProperties(true, BASE_URL, "", 900, TIMEOUT))
			.withMessageContaining("app-key");
		assertThatIllegalStateException()
			.isThrownBy(() -> new RouteWalkProperties(true, BASE_URL, null, 900, TIMEOUT))
			.withMessageContaining("app-key");
	}

	@Test
	void 플래그가_꺼진_환경은_appKey가_비어도_기동을_통과한다() {
		assertThatCode(() -> new RouteWalkProperties(false, BASE_URL, "", 900, TIMEOUT))
			.doesNotThrowAnyException();
	}
}
