package com.msg.fillmap.search.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 카카오 로컬 검색용 RestClient 구성 (MSG-251 §D5). Boot 4.1 엔 RestClient 자동설정이 없어 직접 만든다.
 *
 * 완성된 RestClient 빈이다 — RestClient.Builder 빈이 아니다. AiClient 가 Builder 를 by-type 무한정
 * 주입받고 있어(ai.enabled 환경) 두 번째 Builder 빈은 기동 모호성을 만든다. 완성 RestClient 빈은 이것과
 * kakaoTokenRestClient(MSG-345) 둘이라 주입은 필드명=빈 이름의 by-name 으로 갈린다 — KakaoLocalClient 의
 * 필드명이 이 빈 이름과 같다. KakaoAK 인증 헤더까지 여기서 조립해 KakaoLocalClient 는 호출·파싱만 한다.
 */
@Configuration
public class SearchConfig {

	// 동기 사용자 경로(자동완성 디바운스 뒤 호출) — 카카오 정상 응답은 수백 ms, hang 시 사용자 대기 상한을
	// read 3s 로 끊고 5502 로 수렴시킨다. env 튜닝 수요가 없어 상수(AiConfig 선례) — 필요 시 프로퍼티로 승격.
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

	@Bean
	RestClient kakaoLocalRestClient(KakaoLocalProperties properties) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(CONNECT_TIMEOUT);
		factory.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder()
			.requestFactory(factory)
			.baseUrl(properties.baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.restApiKey())
			.build();
	}
}
