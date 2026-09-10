package com.msg.fillmap.auth.oidc;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 애플 토큰 엔드포인트(교환·취소)용 RestClient 구성 (MSG-594). KakaoTokenClientConfig 미러링 — Boot 4.1 엔
 * RestClient 자동설정이 없어 직접 만든다.
 *
 * baseUrl 은 두지 않는다 — 엔드포인트가 둘(token·revoke)이라 하나에 못 묶고, 호출부가 properties 의 절대 URI 를
 * 쓴다. 완성 RestClient 빈은 이로써 셋(kakaoLocalRestClient·kakaoTokenRestClient·appleTokenRestClient)이라
 * 주입 쪽은 필드명을 빈 이름과 맞춰 by-name 으로 해소한다. RestClient.Builder 빈은 만들지 않는다(AiClient 제약).
 */
@Configuration
public class AppleTokenClientConfig {

	// 로그인·탈퇴의 동기 사용자 경로 — hang 시 대기 상한을 read 3s 로 끊어 2502(로그인)·warn 로그(탈퇴)로 수렴시킨다
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

	@Bean
	RestClient appleTokenRestClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(CONNECT_TIMEOUT);
		factory.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder()
			.requestFactory(factory)
			.build();
	}
}
