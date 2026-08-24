package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.route.config.RouteAiProperties;

/**
 * 플래그 ON 경로의 스프링 구성 검증 (MSG-457 §설정) — RouteControllerTest(기본 꺼짐)의 반대편이다.
 * @ConditionalOnProperty 빈 등록, yml → RouteAiProperties record 바인딩, requestFactory 의 교환 전체
 * 시한(readTimeout=timeout)까지 실제 컨텍스트에서 한 번에 닫는다 (AiClientTest 팩토리 단언 선례).
 */
@SpringBootTest(properties = "route.ai.enabled=true")
@DisplayName("RouteIntentClient — 플래그 ON 빈 구성 (route.ai.enabled=true)")
class RouteIntentClientContextTest {

	@Autowired
	private RouteIntentClient routeIntentClient;

	@Autowired
	private RouteAiProperties properties;

	// 검증: 비기능(운영·성능)
	@Test
	@DisplayName("플래그를 켜면 조건부 빈이 뜨고 yml 바인딩값(PT5S)이 교환 전체 시한으로 걸린다")
	void 플래그를_켜면_클라이언트_빈이_뜨고_타임아웃이_설정값으로_걸린다() {
		// ① @ConditionalOnProperty — 주입 자체가 증명이다 (빈이 없으면 컨텍스트 로드부터 실패한다)
		assertThat(routeIntentClient).isNotNull();

		// ② yml 기본값 → record 바인딩 (enabled 만 테스트 프로퍼티로 켰다)
		assertThat(properties.enabled()).isTrue();
		assertThat(properties.baseUrl()).isEqualTo("http://localhost:8000");
		assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(5));

		// ③ readTimeout = properties.timeout() — HttpRequest.timeout() 이라 교환 전체의 단일 시한이다
		// (AiClientTest 의 highlightRequestFactory 단언 선례. connect 2초는 내부망 전제 고정값)
		JdkClientHttpRequestFactory factory = RouteIntentClient.requestFactory(properties.timeout());
		assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(Duration.ofSeconds(5));
		HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
		assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(2));
	}
}
