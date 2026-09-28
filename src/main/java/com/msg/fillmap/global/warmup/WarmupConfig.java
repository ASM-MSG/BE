package com.msg.fillmap.global.warmup;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 워밍업 러너가 자기 자신을 부르는 RestClient (MSG-608 D2). SearchConfig 와 같은 규약으로 완성 RestClient
 * 빈을 만든다 — RestClient.Builder 빈을 하나 더 두면 AiClient 의 by-type Builder 주입이 모호해져 기동이 깨진다.
 * 주입은 필드명 = 빈 이름(warmupRestClient)의 by-name 으로 갈린다.
 */
@Configuration
public class WarmupConfig {

	// localhost 라 연결은 즉시 된다. 읽기 5초는 인터프리터 구간의 첫 요청(수백 ms~1초대)을 넉넉히 덮으면서
	// 호출 하나가 45초 상한을 혼자 먹지 못하게 끊는 값이다.
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

	@Bean
	RestClient warmupRestClient(WarmupProperties properties) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(CONNECT_TIMEOUT);
		factory.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder()
			.requestFactory(factory)
			.baseUrl(properties.baseUrl())
			.build();
	}
}
