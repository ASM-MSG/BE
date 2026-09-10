package com.msg.fillmap.auth.oidc;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 카카오 토큰 엔드포인트(인가 코드 교환)용 RestClient 구성 (MSG-345). Boot 4.1 엔 RestClient 자동설정이 없어
 * 직접 만든다 — SearchConfig 선례.
 *
 * 완성된 RestClient 빈이다 — RestClient.Builder 빈이 아니다. AiClient 가 Builder 를 by-type 으로 주입받고 있어
 * 두 번째 Builder 빈은 기동 모호성을 만든다. 완성 RestClient 빈은 kakaoLocalRestClient 에 이어 두 번째라
 * 주입 쪽은 필드명을 빈 이름과 맞춰 by-name 으로 해소한다.
 */
@Configuration
public class KakaoTokenClientConfig {

	// 로그인은 검색과 같은 동기 사용자 경로 — 카카오 정상 응답은 수백 ms 이고, hang 시 사용자 대기 상한을 read 3s 로
	// 끊어 2502 로 수렴시킨다. env 튜닝 수요가 없어 상수(SearchConfig 선례) — 필요해지면 프로퍼티로 승격.
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

	@Bean
	RestClient kakaoTokenRestClient(KakaoOidcProperties properties) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(CONNECT_TIMEOUT);
		factory.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder()
			.requestFactory(factory)
			.baseUrl(properties.tokenUri())
			.build();
	}
}
